package com.logdashboard.watcher;

import com.logdashboard.config.DashboardConfig;
import com.logdashboard.config.ServerPath;
import com.logdashboard.model.LogIssue;
import com.logdashboard.parser.LogParser;
import com.logdashboard.util.IconvConverter;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Watches specified directories for log file changes and detects issues.
 *
 * Performance notes:
 * - Each poll lists every watched directory once, filters by file name before touching
 *   the file system, and reads file attributes with a single call per file.
 * - Polls use a fixed delay, so a slow cycle (e.g. a sluggish network mount) never causes
 *   back-to-back polling that pins a CPU core.
 * - All tracking state is mutated only on the watcher thread; web threads read snapshots.
 * - Warnings about missing/unreadable paths are logged when the state changes, not every poll.
 */
public class LogFileWatcher {

    private final DashboardConfig config;
    private final LogParser parser;
    private final Consumer<LogIssue> issueCallback;
    private final Consumer<String> statusCallback;
    private final ScheduledExecutorService scheduler;
    private volatile List<Pattern> filePatterns;
    private volatile List<String> filePatternsSource;  // Configured patterns the compiled ones came from

    private final Map<Path, TrackedFile> trackedFiles = new ConcurrentHashMap<>();
    private final Map<String, PathStatus> pathStatuses = new ConcurrentHashMap<>();


    // Limits
    private static final int MAX_TRACKED_FILES = 10000;
    private static final int MAX_READ_BYTES_PER_POLL = 4 * 1024 * 1024;  // Per file, per poll
    private static final int MAX_LINES_PER_READ = 10000;
    private static final int MAX_LINE_LENGTH = 10000;
    private static final long LINE_COUNT_MAX_FILE_SIZE = 10 * 1024 * 1024;

    // A file that appears after startup is read from the beginning only if it was modified
    // after the previous poll started (minus this slack for file server clock skew).
    // Older files - e.g. rotated archives moved into the directory - start at the end.
    private static final long NEW_FILE_MTIME_SLACK_MS = 5000;
    private static final long SLOW_POLL_WARNING_INTERVAL_MS = 10 * 60 * 1000;

    private static final byte CR = 0x0D;
    private static final Pattern ICONV_LINE_SPLIT = Pattern.compile("\r\n|[\n\r]");

    private volatile boolean running;
    private volatile boolean verboseLogging = false;
    private volatile LogParser.RepeatListener repeatListener;

    // Poll statistics (written by the watcher thread, read by diagnostics)
    private volatile long lastPollAt;
    private volatile long lastPollDurationMs;
    private volatile long pollCount;
    private volatile String lastPollError;

    // Watcher thread only
    private long previousPollStartedAt;
    private long lastSlowPollWarningAt;
    private boolean trackedFileLimitWarned;

    /** State of a single watched path (directory or file). */
    public enum PathState { OK, EMPTY, MISSING, NOT_MATCHING, ERROR }

    private static final class PathStatus {
        final String serverName;
        final String path;
        final PathState state;
        final String message;
        final int matchingFiles;
        final long checkedAt;

        PathStatus(String serverName, String path, PathState state, String message, int matchingFiles) {
            this.serverName = serverName;
            this.path = path;
            this.state = state;
            this.message = message;
            this.matchingFiles = matchingFiles;
            this.checkedAt = System.currentTimeMillis();
        }
    }

    /** Encoding used to decode a tracked file. */
    private static final class EncodingInfo {
        final Charset charset;       // null => convert with external iconv
        final boolean ebcdic;
        final String iconvEncoding;  // set for EBCDIC encodings
        final String label;

        EncodingInfo(Charset charset, boolean ebcdic, String iconvEncoding, String label) {
            this.charset = charset;
            this.ebcdic = ebcdic;
            this.iconvEncoding = iconvEncoding;
            this.label = label;
        }
    }

    /** Tracking state for one log file. */
    private static final class TrackedFile {
        final Path path;
        final String sourceKey;
        final String serverName;
        final EncodingInfo encoding;
        volatile long position;
        volatile int lineNumber;
        volatile long lastSeenSize = -1;
        volatile Object fileKey;
        volatile String lastError;

        TrackedFile(Path path, String sourceKey, String serverName, EncodingInfo encoding) {
            this.path = path;
            this.sourceKey = sourceKey;
            this.serverName = serverName;
            this.encoding = encoding;
        }

        TrackedFile snapshot() {
            TrackedFile copy = new TrackedFile(path, sourceKey, serverName, encoding);
            copy.position = position;
            copy.lineNumber = lineNumber;
            copy.lastSeenSize = lastSeenSize;
            copy.fileKey = fileKey;
            return copy;
        }
    }

    /** A matching file found while listing a watched path. */
    private static final class Candidate {
        final Path path;
        final BasicFileAttributes attrs;
        final ServerPath source;
        final String sourceKey;

        Candidate(Path path, BasicFileAttributes attrs, ServerPath source, String sourceKey) {
            this.path = path;
            this.attrs = attrs;
            this.source = source;
            this.sourceKey = sourceKey;
        }
    }

    /**
     * Result of reading new lines from a file.
     */
    private static class ReadResult {
        final List<String> lines;
        final long bytesConsumed;
        final int lineCount;

        ReadResult(List<String> lines, long bytesConsumed, int lineCount) {
            this.lines = lines;
            this.bytesConsumed = bytesConsumed;
            this.lineCount = lineCount;
        }
    }

    public LogFileWatcher(DashboardConfig config, Consumer<LogIssue> issueCallback,
                          Consumer<String> statusCallback) {
        this.config = config;
        this.parser = new LogParser(config);
        this.issueCallback = issueCallback;
        this.statusCallback = statusCallback;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LogFileWatcher");
            t.setDaemon(true);
            return t;
        });
        this.filePatternsSource = snapshot(config.getFilePatterns());
        this.filePatterns = compileFilePatterns(filePatternsSource);
        this.running = false;
    }

    /**
     * Recompiles file patterns if the configured patterns changed (e.g. on the Config page).
     * The following scan prunes files that no longer match and picks up newly matching ones.
     */
    private void refreshFilePatterns() {
        List<String> configured = snapshot(config.getFilePatterns());
        if (!configured.isEmpty() && !configured.equals(filePatternsSource)) {
            filePatterns = compileFilePatterns(configured);
            filePatternsSource = configured;
            updateStatus("File patterns updated: " + configured);
        }
    }

    /**
     * Applies file patterns from the configuration file. Empty lists are ignored because
     * they would stop watching every file.
     */
    public void updateFilePatterns(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            updateStatus("Ignoring empty file pattern list");
            return;
        }
        if (!patterns.equals(snapshot(config.getFilePatterns()))) {
            config.setFilePatterns(new ArrayList<>(patterns));
        }
    }

    private List<Pattern> compileFilePatterns(List<String> patterns) {
        List<Pattern> compiled = new ArrayList<>();
        if (patterns == null) {
            return compiled;
        }
        for (String pattern : patterns) {
            // Convert glob pattern to regex
            String regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
            compiled.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
        }
        return compiled;
    }

    /**
     * Starts watching the configured directories for log file changes.
     */
    public void start() {
        if (running) {
            return;
        }
        running = true;

        updateStatus("Starting log file watcher...");
        if (IconvConverter.isIconvAvailable()) {
            updateStatus("iconv command is available for encoding conversion");
        } else {
            updateStatus("iconv command not available, using Java charset handling");
        }

        // Initial scan: existing files are tracked from their current end
        runOnWatcherThread("initial scan", () -> scanSources(getActiveSources(), true, true), 0);

        int interval = Math.max(1, config.getPollingIntervalSeconds());
        scheduler.scheduleWithFixedDelay(this::pollFiles, interval, interval, TimeUnit.SECONDS);

        updateStatus("Watching " + getActiveSources().size() + " path(s), " + trackedFiles.size() + " file(s)");
    }

    /**
     * Stops the file watcher.
     */
    public void stop() {
        running = false;
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        updateStatus("Watcher stopped");
    }

    /**
     * Polls all watched paths for changes. Never throws: an exception escaping a
     * scheduled task would silently cancel all future polls.
     */
    private void pollFiles() {
        if (!running) {
            return;
        }
        long start = System.currentTimeMillis();
        try {
            scanSources(getActiveSources(), false, true);
            lastPollError = null;
        } catch (Exception | StackOverflowError e) {
            lastPollError = e.toString();
            updateStatus("Error during poll cycle: " + e);
        } finally {
            long duration = System.currentTimeMillis() - start;
            lastPollDurationMs = duration;
            lastPollAt = start;
            pollCount++;

            long intervalMs = Math.max(1, config.getPollingIntervalSeconds()) * 1000L;
            if (duration > intervalMs && start - lastSlowPollWarningAt > SLOW_POLL_WARNING_INTERVAL_MS) {
                lastSlowPollWarningAt = start;
                updateStatus("Warning: poll cycle took " + duration + "ms, longer than the polling interval ("
                    + intervalMs + "ms). Consider increasing pollingIntervalSeconds or reducing watched files.");
            }
        }
    }

    /**
     * Lists the given sources, processes matching files, and prunes files that disappeared.
     *
     * @param initial   true to track newly found files from their end (startup / newly added path)
     * @param fullCycle true when sources contains every active source
     */
    private void scanSources(List<ServerPath> sources, boolean initial, boolean fullCycle) {
        long cycleStart = System.currentTimeMillis();
        refreshFilePatterns();
        long newFileThreshold = initial ? Long.MAX_VALUE : previousPollStartedAt - NEW_FILE_MTIME_SLACK_MS;

        Map<Path, Candidate> candidates = new LinkedHashMap<>();
        Set<String> listedSources = new HashSet<>();
        Set<String> allSourceKeys = new HashSet<>();
        for (ServerPath source : sources) {
            String key = sourceKey(source.getServerName(), source.getPath());
            allSourceKeys.add(key);
            if (listSource(source, key, candidates)) {
                listedSources.add(key);
            }
        }

        // Files that vanished or were replaced at their path, keyed by inode, so a renamed
        // (rotated) file continues from where it was instead of being re-read from the start.
        Map<Object, TrackedFile> movedFiles = new HashMap<>();
        for (TrackedFile tf : trackedFiles.values()) {
            if (tf.fileKey == null) {
                continue;
            }
            Candidate current = candidates.get(tf.path);
            boolean goneOrReplaced = current == null
                ? listedSources.contains(tf.sourceKey)
                : !tf.fileKey.equals(current.attrs.fileKey());
            if (goneOrReplaced) {
                movedFiles.put(tf.fileKey, tf.snapshot());
            }
        }

        for (Candidate candidate : candidates.values()) {
            processCandidate(candidate, newFileThreshold, movedFiles);
        }

        // Prune files that no longer exist in a successfully listed path, or whose path was removed
        trackedFiles.values().removeIf(tf -> !candidates.containsKey(tf.path)
            && (listedSources.contains(tf.sourceKey) || (fullCycle && !allSourceKeys.contains(tf.sourceKey))));
        if (trackedFiles.size() < MAX_TRACKED_FILES) {
            trackedFileLimitWarned = false;
        }

        if (fullCycle) {
            pathStatuses.keySet().retainAll(allSourceKeys);
            previousPollStartedAt = cycleStart;
        }
    }

    /**
     * Lists one watched path and adds matching regular files to candidates.
     *
     * @return true if the path was listed successfully (its files can be pruned if missing)
     */
    private boolean listSource(ServerPath source, String key, Map<Path, Candidate> candidates) {
        Path watchPath;
        try {
            watchPath = Paths.get(source.getPath());
        } catch (InvalidPathException e) {
            setPathStatus(source, key, PathState.ERROR, "Invalid path (" + e.getMessage() + ")", 0);
            return false;
        }

        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(watchPath, BasicFileAttributes.class);
        } catch (NoSuchFileException e) {
            setPathStatus(source, key, PathState.MISSING, "Watch path does not exist", 0);
            return false;
        } catch (IOException e) {
            setPathStatus(source, key, PathState.ERROR, "Cannot access path (" + e + ")", 0);
            return false;
        }

        if (attrs.isDirectory()) {
            int matching = 0;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(watchPath)) {
                for (Path file : stream) {
                    // Cheap name check first, so non-matching files never cost a stat call
                    if (!matchesFilePattern(file)) {
                        continue;
                    }
                    BasicFileAttributes fileAttrs;
                    try {
                        fileAttrs = Files.readAttributes(file, BasicFileAttributes.class);
                    } catch (IOException e) {
                        continue;  // Removed between listing and stat
                    }
                    if (!fileAttrs.isRegularFile()) {
                        continue;
                    }
                    matching++;
                    candidates.putIfAbsent(file, new Candidate(file, fileAttrs, source, key));
                }
            } catch (IOException | DirectoryIteratorException e) {
                setPathStatus(source, key, PathState.ERROR, "Cannot read directory (" + e + ")", 0);
                return false;
            }
            if (matching == 0) {
                setPathStatus(source, key, PathState.EMPTY, "No files matching " + config.getFilePatterns(), 0);
            } else {
                setPathStatus(source, key, PathState.OK, null, matching);
            }
            return true;
        }

        if (attrs.isRegularFile()) {
            if (matchesFilePattern(watchPath)) {
                candidates.putIfAbsent(watchPath, new Candidate(watchPath, attrs, source, key));
                setPathStatus(source, key, PathState.OK, null, 1);
            } else {
                setPathStatus(source, key, PathState.NOT_MATCHING,
                    "File does not match " + config.getFilePatterns(), 0);
            }
            return true;
        }

        setPathStatus(source, key, PathState.ERROR, "Not a regular file or directory", 0);
        return false;
    }

    /**
     * Starts tracking a newly found file if needed, then checks it for new content.
     */
    private void processCandidate(Candidate candidate, long newFileThreshold, Map<Object, TrackedFile> movedFiles) {
        TrackedFile tf = trackedFiles.get(candidate.path);
        try {
            if (tf == null) {
                tf = startTracking(candidate, newFileThreshold, movedFiles);
                if (tf == null) {
                    return;
                }
            }
            checkFileForChanges(tf, candidate.attrs);
            tf.lastError = null;
        } catch (IOException | RuntimeException | StackOverflowError e) {
            String message = e.toString();
            if (tf == null || !message.equals(tf.lastError)) {
                updateStatus("Error reading " + candidate.path + serverInfo(candidate.source.getServerName())
                    + " - " + message);
            }
            if (tf != null) {
                tf.lastError = message;
            }
        }
    }

    private TrackedFile startTracking(Candidate candidate, long newFileThreshold, Map<Object, TrackedFile> movedFiles) {
        if (trackedFiles.size() >= MAX_TRACKED_FILES) {
            if (!trackedFileLimitWarned) {
                trackedFileLimitWarned = true;
                updateStatus("Warning: Maximum tracked files limit (" + MAX_TRACKED_FILES
                    + ") reached. New files are ignored until tracked files are removed.");
            }
            return null;
        }

        String fileName = candidate.path.getFileName().toString();
        String serverName = candidate.source.getServerName();
        EncodingInfo encoding = resolveEncoding(candidate.source, fileName);
        TrackedFile tf = new TrackedFile(candidate.path, candidate.sourceKey, serverName, encoding);
        Object fileKey = candidate.attrs.fileKey();
        tf.fileKey = fileKey;

        String encodingInfo = StandardCharsets.UTF_8.equals(encoding.charset) ? "" : " (" + encoding.label + ")";
        TrackedFile previous = fileKey != null ? movedFiles.remove(fileKey) : null;

        if (previous != null) {
            tf.position = previous.position;
            tf.lineNumber = previous.lineNumber;
            tf.lastSeenSize = previous.lastSeenSize;
            updateStatus("Tracking renamed file: " + previous.path.getFileName() + " -> " + fileName
                + serverInfo(serverName));
        } else if (candidate.attrs.lastModifiedTime().toMillis() < newFileThreshold) {
            long size = candidate.attrs.size();
            tf.position = size;
            tf.lastSeenSize = size;
            if (size < LINE_COUNT_MAX_FILE_SIZE) {
                try {
                    tf.lineNumber = countLines(candidate.path, encoding.ebcdic);
                } catch (IOException e) {
                    tf.lineNumber = 0;
                }
            }
            updateStatus("Tracking: " + fileName + serverInfo(serverName) + encodingInfo);
        } else {
            updateStatus("New file detected: " + fileName + serverInfo(serverName) + encodingInfo
                + " - reading from start");
        }

        trackedFiles.put(candidate.path, tf);
        return tf;
    }

    /**
     * Resolves how to decode a file, honoring per-file encoding overrides.
     * EBCDIC is decoded in-process when the JVM supports the code page (avoids spawning
     * an iconv process per read); external iconv is used only as a fallback.
     */
    private EncodingInfo resolveEncoding(ServerPath source, String fileName) {
        String encoding = source.getEncodingForFile(fileName);

        if (IconvConverter.isEbcdicEncoding(encoding)) {
            String iconvEncoding = IconvConverter.normalizeToIconvEncoding(encoding);
            Charset charset = null;
            try {
                Charset resolved = IconvConverter.iconvEncodingToJavaCharset(iconvEncoding);
                if (!StandardCharsets.UTF_8.equals(resolved)) {
                    charset = resolved;
                }
            } catch (RuntimeException e) {
                // Code page not available in this JVM
            }
            if (charset == null && !IconvConverter.isIconvAvailable()) {
                charset = StandardCharsets.ISO_8859_1;
            }
            return new EncodingInfo(charset, true, iconvEncoding,
                iconvEncoding + (charset == null ? "/iconv" : ""));
        }

        String normalized = IconvConverter.normalizeToIconvEncoding(encoding);
        if ("ISO8859-1".equals(normalized)) {
            return new EncodingInfo(StandardCharsets.ISO_8859_1, false, null, "ISO8859-1");
        }
        if (!"UTF-8".equals(normalized) && encoding != null) {
            try {
                Charset charset = Charset.forName(encoding.trim());
                // Line splitting works on bytes, so only ASCII-compatible charsets are usable
                if (Arrays.equals("\n\r".getBytes(charset), new byte[] {0x0A, CR})) {
                    return new EncodingInfo(charset, false, null, charset.name());
                }
            } catch (RuntimeException e) {
                // Unknown charset; fall back to UTF-8
            }
        }
        return new EncodingInfo(StandardCharsets.UTF_8, false, null, "UTF-8");
    }

    /**
     * Counts line terminators in a file by scanning bytes (no character decoding).
     */
    private static int countLines(Path file, boolean ebcdic) throws IOException {
        int count = 0;
        byte previous = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                for (int i = 0; i < read; i++) {
                    byte b = buffer[i];
                    if (b == CR) {
                        count++;
                    } else if (isLineFeed(b, ebcdic) && previous != CR) {
                        count++;
                    }
                    previous = b;
                }
            }
        }
        return count;
    }

    private static boolean isLineFeed(byte b, boolean ebcdic) {
        // EBCDIC: 0x15 = NL, 0x25 = LF
        return ebcdic ? (b == 0x15 || b == 0x25) : b == 0x0A;
    }

    /**
     * Checks a tracked file for new content, handling truncation and replacement.
     */
    private void checkFileForChanges(TrackedFile tf, BasicFileAttributes attrs) throws IOException {
        long currentSize = attrs.size();
        Object fileKey = attrs.fileKey();
        String fileName = tf.path.getFileName().toString();
        String serverInfo = serverInfo(tf.serverName);

        boolean replaced = tf.fileKey != null && fileKey != null && !tf.fileKey.equals(fileKey);
        tf.fileKey = fileKey;
        if (replaced || currentSize < tf.position) {
            // Rotated (new file at this path) or truncated: all current content is new
            updateStatus("File rotated: " + fileName + serverInfo + " - reading from start");
            tf.position = 0;
            tf.lineNumber = 0;
            tf.lastSeenSize = -1;
        }

        if (currentSize <= tf.position) {
            tf.lastSeenSize = currentSize;
            return;
        }

        // An unterminated last line is held back until the writer finishes it, or until
        // the file stops growing for one poll.
        boolean flushPartial = currentSize == tf.lastSeenSize;
        tf.lastSeenSize = currentSize;

        if (verboseLogging) {
            updateStatus("New content detected in " + fileName + serverInfo + " (" + (currentSize - tf.position)
                + " bytes, pos " + tf.position + " -> " + currentSize + ")");
        }

        ReadResult result = readNewLines(tf, currentSize, flushPartial);
        int firstLineNumber = tf.lineNumber + 1;
        // Advance before parsing so a parser failure can never cause the same bytes to be re-read forever
        tf.position += result.bytesConsumed;
        tf.lineNumber += result.lineCount;

        if (result.lines.isEmpty()) {
            if (verboseLogging && result.bytesConsumed == 0) {
                updateStatus("Waiting for end of line in " + fileName + serverInfo);
            }
            return;
        }

        if (verboseLogging) {
            updateStatus("Read " + result.lines.size() + " lines from " + fileName + serverInfo);
            int previewLines = Math.min(3, result.lines.size());
            for (int i = 0; i < previewLines; i++) {
                String line = result.lines.get(i);
                if (line.length() > 100) {
                    line = line.substring(0, 100) + "...";
                }
                updateStatus("  Line " + (firstLineNumber + i) + ": " + line);
            }
        }

        LogParser.ParseResult parsed = parser.parse(tf.serverName, fileName, result.lines, firstLineNumber);
        List<LogIssue> issues = parsed.issues;

        if (verboseLogging && issues.isEmpty()) {
            updateStatus("No issues detected in " + result.lines.size() + " lines from " + fileName + serverInfo);
        } else if (!issues.isEmpty()) {
            updateStatus("Detected " + issues.size() + " issue(s) in " + fileName + serverInfo);
        }

        for (LogIssue issue : issues) {
            issueCallback.accept(issue);
        }

        // Applied after new issues are stored, since repeats may refer to them
        LogParser.RepeatListener listener = repeatListener;
        if (listener != null) {
            for (LogParser.Repeat repeat : parsed.repeats) {
                listener.onRepeat(repeat.issueId, repeat.count, repeat.lastSeenAt);
            }
        }
    }

    /**
     * Reads complete lines from the tracked position. Lines are split on bytes before
     * decoding, so multi-byte characters are never split across reads and byte positions
     * are exact.
     */
    private ReadResult readNewLines(TrackedFile tf, long currentSize, boolean flushPartial) throws IOException {
        long available = currentSize - tf.position;
        byte[] buffer = new byte[(int) Math.min(available, MAX_READ_BYTES_PER_POLL)];
        int length = 0;
        try (FileChannel channel = FileChannel.open(tf.path, StandardOpenOption.READ)) {
            ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
            while (byteBuffer.hasRemaining()) {
                int read = channel.read(byteBuffer, tf.position + length);
                if (read <= 0) {
                    break;
                }
                length += read;
            }
        }

        EncodingInfo encoding = tf.encoding;
        boolean decodeLines = encoding.charset != null;
        boolean atEof = length == available;
        List<String> lines = new ArrayList<>();
        int lineStart = 0;
        int consumed = 0;
        int lineCount = 0;

        for (int i = 0; i < length && lineCount < MAX_LINES_PER_READ; i++) {
            byte b = buffer[i];
            int terminatorLength;
            if (isLineFeed(b, encoding.ebcdic)) {
                terminatorLength = 1;
            } else if (b == CR) {
                if (i + 1 < length) {
                    terminatorLength = isLineFeed(buffer[i + 1], encoding.ebcdic) ? 2 : 1;
                } else if (flushPartial && atEof) {
                    terminatorLength = 1;
                } else {
                    break;  // The LF of a CRLF may not be written yet
                }
            } else {
                continue;
            }
            if (decodeLines) {
                lines.add(decode(buffer, lineStart, i, encoding.charset));
            }
            lineCount++;
            i += terminatorLength - 1;
            lineStart = i + 1;
            consumed = lineStart;
        }

        if (consumed < length && lineCount < MAX_LINES_PER_READ) {
            boolean oversizedLine = lineCount == 0 && length == MAX_READ_BYTES_PER_POLL;
            if ((flushPartial && atEof) || oversizedLine) {
                int end = length;
                if (end > lineStart && buffer[end - 1] == CR) {
                    end--;
                }
                if (decodeLines) {
                    lines.add(decode(buffer, lineStart, end, encoding.charset));
                }
                lineCount++;
                consumed = length;
            }
        }

        if (!decodeLines && consumed > 0) {
            lines = convertWithIconv(Arrays.copyOf(buffer, consumed), encoding.iconvEncoding);
        }

        return new ReadResult(lines, consumed, lineCount);
    }

    private static String decode(byte[] buffer, int start, int end, Charset charset) {
        return new String(buffer, start, Math.min(end - start, MAX_LINE_LENGTH), charset);
    }

    /** Converts a block of complete EBCDIC lines with one iconv invocation. */
    private static List<String> convertWithIconv(byte[] block, String iconvEncoding) {
        String text;
        try {
            text = IconvConverter.convertEbcdicToReadable(block, iconvEncoding);
        } catch (IOException e) {
            text = new String(block, StandardCharsets.ISO_8859_1);
        }
        List<String> lines = new ArrayList<>();
        for (String line : ICONV_LINE_SPLIT.split(text)) {
            lines.add(line.length() > MAX_LINE_LENGTH ? line.substring(0, MAX_LINE_LENGTH) : line);
        }
        return lines;
    }

    /**
     * Checks if a file matches the configured file patterns.
     */
    private boolean matchesFilePattern(Path file) {
        Path name = file.getFileName();
        if (name == null) {
            return false;
        }
        String fileName = name.toString();
        for (Pattern pattern : filePatterns) {
            if (pattern.matcher(fileName).matches()) {
                return true;
            }
        }
        return false;
    }

    private void setPathStatus(ServerPath source, String key, PathState state, String message, int matchingFiles) {
        PathStatus previous = pathStatuses.put(key,
            new PathStatus(source.getServerName(), source.getPath(), state, message, matchingFiles));

        // Log only on state transitions to avoid flooding the log every poll
        if (previous != null && previous.state == state) {
            return;
        }
        String serverInfo = serverInfo(source.getServerName());
        if (state == PathState.OK) {
            if (previous != null) {
                updateStatus("Watch path available again: " + source.getPath() + serverInfo
                    + " (" + matchingFiles + " matching file(s))");
            }
        } else {
            updateStatus("Warning: " + message + ": " + source.getPath() + serverInfo);
        }
    }

    /**
     * Returns all watched sources (legacy paths, configured servers, dynamically added),
     * de-duplicated by server name and normalized path.
     */
    private List<ServerPath> getActiveSources() {
        Map<String, ServerPath> sources = new LinkedHashMap<>();
        for (String path : snapshot(config.getWatchPaths())) {
            if (path != null && !path.isBlank()) {
                sources.putIfAbsent(sourceKey(null, path), new ServerPath(null, path));
            }
        }
        for (ServerPath server : snapshot(config.getServers())) {
            if (server != null && server.getPath() != null && !server.getPath().isBlank()) {
                sources.putIfAbsent(sourceKey(server.getServerName(), server.getPath()), server);
            }
        }
        return new ArrayList<>(sources.values());
    }

    /** Copies a list that may be modified concurrently by web requests. */
    private static <T> List<T> snapshot(List<T> list) {
        if (list == null) {
            return Collections.emptyList();
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return new ArrayList<>(list);
            } catch (ConcurrentModificationException e) {
                // Retry
            }
        }
        return Collections.emptyList();
    }

    private static String sourceKey(String serverName, String path) {
        String normalized;
        try {
            normalized = Paths.get(path).toAbsolutePath().normalize().toString();
        } catch (InvalidPathException e) {
            normalized = path;
        }
        return (serverName != null ? serverName : "") + "::" + normalized;
    }

    private static String serverInfo(String serverName) {
        return serverName != null ? " [" + serverName + "]" : "";
    }

    /**
     * Runs a task on the watcher thread so tracking state is only mutated by one thread.
     *
     * @param timeoutSeconds how long to wait for completion; 0 waits until done
     */
    private void runOnWatcherThread(String description, Runnable task, long timeoutSeconds) {
        Future<?> future;
        try {
            future = scheduler.submit(() -> {
                try {
                    task.run();
                } catch (Exception | StackOverflowError e) {
                    updateStatus("Error during " + description + ": " + e);
                }
            });
        } catch (RejectedExecutionException e) {
            updateStatus("Watcher is stopped; skipped " + description);
            return;
        }
        try {
            if (timeoutSeconds > 0) {
                future.get(timeoutSeconds, TimeUnit.SECONDS);
            } else {
                future.get();
            }
        } catch (TimeoutException e) {
            updateStatus("Still running " + description + " in the background");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // Already reported inside the task
        }
    }

    /**
     * Sets the listener that receives repeat counts for issues suppressed by deduplication.
     */
    public void setRepeatListener(LogParser.RepeatListener repeatListener) {
        this.repeatListener = repeatListener;
    }

    /**
     * Enables or disables verbose logging for debugging.
     */
    public void setVerboseLogging(boolean enabled) {
        this.verboseLogging = enabled;
        updateStatus("Verbose logging " + (enabled ? "enabled" : "disabled"));
    }

    /**
     * Returns whether verbose logging is enabled.
     */
    public boolean isVerboseLogging() {
        return verboseLogging;
    }

    private void updateStatus(String status) {
        if (statusCallback != null) {
            statusCallback.accept(status);
        }
    }

    /**
     * Returns the list of currently tracked files.
     */
    public Set<Path> getTrackedFiles() {
        return new HashSet<>(trackedFiles.keySet());
    }

    /**
     * Returns the server name for a tracked file.
     */
    public String getServerName(Path file) {
        TrackedFile tf = trackedFiles.get(file);
        return tf != null ? tf.serverName : null;
    }

    /**
     * Forces a rescan of all watched directories.
     */
    public void rescan() {
        runOnWatcherThread("rescan", () -> {
            trackedFiles.clear();
            pathStatuses.clear();
            scanSources(getActiveSources(), true, true);
        }, 60);
    }

    /**
     * Adds new server paths to watch dynamically.
     * This is called when the configuration file is updated with new servers.
     *
     * @param newServers List of new ServerPath objects to watch
     */
    public void addServerPaths(List<ServerPath> newServers) {
        if (newServers == null || newServers.isEmpty()) {
            return;
        }

        List<ServerPath> toScan = new ArrayList<>();
        for (ServerPath server : newServers) {
            if (server == null || server.getPath() == null || server.getPath().isEmpty()) {
                continue;
            }
            if (registerServerPath(server)) {
                toScan.add(server);
            }
        }

        if (!toScan.isEmpty()) {
            runOnWatcherThread("scan of new path(s)", () -> scanSources(toScan, true, false), 30);
            updateStatus("Now watching " + getActiveSources().size() + " path(s)");
        }
    }

    /**
     * Adds a single server path to watch dynamically.
     *
     * @param serverName The server name (can be null for legacy paths)
     * @param path The path to watch
     */
    public void addServerPath(String serverName, String path) {
        addServerPath(serverName, path, null, false);
    }

    /**
     * Adds a single server path to watch dynamically with custom encoding.
     *
     * @param serverName The server name (can be null for legacy paths)
     * @param path The path to watch
     * @param encoding The character encoding (e.g., "UTF-8", "EBCDIC", "Cp1047", "IBM-1047")
     */
    public void addServerPath(String serverName, String path, String encoding) {
        addServerPath(serverName, path, encoding, false);
    }

    /**
     * Adds a single server path to watch dynamically with custom encoding and iconv option.
     *
     * @param serverName The server name (can be null for legacy paths)
     * @param path The path to watch
     * @param encoding The character encoding (e.g., "UTF-8", "EBCDIC", "Cp1047", "IBM-1047")
     * @param useIconv Whether to use external iconv command for encoding conversion
     */
    public void addServerPath(String serverName, String path, String encoding, boolean useIconv) {
        if (path == null || path.isEmpty()) {
            return;
        }
        addServerPaths(Collections.singletonList(new ServerPath(serverName, path, null, encoding, useIconv)));
    }

    /**
     * Stops watching the given paths immediately: removes them from the live configuration
     * (if still present) and drops their tracked files.
     */
    public void removeServerPaths(List<ServerPath> removedServers) {
        if (removedServers == null || removedServers.isEmpty()) {
            return;
        }

        Set<String> keys = new HashSet<>();
        for (ServerPath removed : removedServers) {
            if (removed == null || removed.getPath() == null) {
                continue;
            }
            String key = sourceKey(removed.getServerName(), removed.getPath());
            keys.add(key);
            if (config.getServers() != null) {
                config.getServers().removeIf(s -> s != null && s.getPath() != null
                    && key.equals(sourceKey(s.getServerName(), s.getPath())));
            }
            if (removed.getServerName() == null && config.getWatchPaths() != null) {
                config.getWatchPaths().removeIf(p -> p != null && key.equals(sourceKey(null, p)));
            }
        }
        if (keys.isEmpty()) {
            return;
        }

        runOnWatcherThread("removal of watch path(s)", () -> {
            int before = trackedFiles.size();
            trackedFiles.values().removeIf(tf -> keys.contains(tf.sourceKey));
            pathStatuses.keySet().removeAll(keys);
            updateStatus("Stopped watching " + keys.size() + " path(s); "
                + (before - trackedFiles.size()) + " file(s) no longer tracked");
        }, 30);
    }

    private List<ServerPath> serverList() {
        if (config.getServers() == null) {
            config.setServers(new CopyOnWriteArrayList<>());
        }
        return config.getServers();
    }

    private List<String> watchPathList() {
        if (config.getWatchPaths() == null) {
            config.setWatchPaths(new CopyOnWriteArrayList<>());
        }
        return config.getWatchPaths();
    }

    /**
     * Adds a path to the live configuration unless it is already watched (e.g. added through the UI,
     * which also updates the config and triggers the config file watcher).
     *
     * @return true if the path needs an initial scan
     */
    private boolean registerServerPath(ServerPath server) {
        String key = sourceKey(server.getServerName(), server.getPath());
        boolean alreadyActive = false;
        for (ServerPath active : getActiveSources()) {
            if (key.equals(sourceKey(active.getServerName(), active.getPath()))) {
                alreadyActive = true;
                break;
            }
        }
        if (!alreadyActive) {
            // Add to the live configuration so the path is listed (and removable) on the Config page
            if (server.getServerName() == null) {
                watchPathList().add(server.getPath());
            } else {
                serverList().add(server);
            }
            String encodingInfo = server.getEncoding() != null ? " (" + server.getEncoding() + ")" : "";
            updateStatus("Adding new server path: " + server.getPath() + serverInfo(server.getServerName()) + encodingInfo);
        }
        // Scan immediately unless this path has already been scanned
        return !pathStatuses.containsKey(key);
    }

    /**
     * Returns true if the watcher is currently running.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns the health of a watched path, or null if it has not been checked yet.
     */
    public Map<String, Object> getPathStatus(String serverName, String path) {
        PathStatus status = pathStatuses.get(sourceKey(serverName, path));
        return status != null ? pathStatusToMap(status) : null;
    }

    /**
     * Returns a short summary of watcher health for status displays.
     */
    public Map<String, Object> getHealthSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("running", running);
        summary.put("trackedFilesCount", trackedFiles.size());
        summary.put("lastPollAt", lastPollAt);
        summary.put("lastPollDurationMs", lastPollDurationMs);
        summary.put("pollCount", pollCount);
        summary.put("lastPollError", lastPollError);

        int problemPaths = 0;
        for (PathStatus status : pathStatuses.values()) {
            if (status.state != PathState.OK) {
                problemPaths++;
            }
        }
        summary.put("watchedPathCount", pathStatuses.size());
        summary.put("problemPathCount", problemPaths);
        return summary;
    }

    private static Map<String, Object> pathStatusToMap(PathStatus status) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("state", status.state.name());
        map.put("message", status.message);
        map.put("matchingFiles", status.matchingFiles);
        map.put("checkedAt", status.checkedAt);
        return map;
    }

    /**
     * Returns diagnostic information about the watcher status.
     * Useful for troubleshooting.
     */
    public Map<String, Object> getDiagnostics() {
        Map<String, Object> diagnostics = new LinkedHashMap<>(getHealthSummary());
        diagnostics.put("maxTrackedFiles", MAX_TRACKED_FILES);
        diagnostics.put("pollingIntervalSeconds", config.getPollingIntervalSeconds());

        List<String> patterns = new ArrayList<>();
        for (Pattern p : filePatterns) {
            patterns.add(p.pattern());
        }
        diagnostics.put("filePatterns", patterns);

        List<Map<String, Object>> configuredPaths = new ArrayList<>();
        for (ServerPath source : getActiveSources()) {
            Map<String, Object> pathInfo = new LinkedHashMap<>();
            pathInfo.put("path", source.getPath());
            pathInfo.put("serverName", source.getServerName());
            pathInfo.put("type", source.getServerName() != null ? "server" : "legacy");
            pathInfo.put("encoding", source.getEncoding());
            PathStatus status = pathStatuses.get(sourceKey(source.getServerName(), source.getPath()));
            if (status != null) {
                pathInfo.put("exists", status.state != PathState.MISSING);
                pathInfo.putAll(pathStatusToMap(status));
            }
            configuredPaths.add(pathInfo);
        }
        diagnostics.put("configuredPaths", configuredPaths);

        List<Map<String, Object>> trackedFilesList = new ArrayList<>();
        for (TrackedFile tf : trackedFiles.values()) {
            Map<String, Object> fileInfo = new LinkedHashMap<>();
            fileInfo.put("path", tf.path.toString());
            fileInfo.put("fileName", tf.path.getFileName().toString());
            fileInfo.put("serverName", tf.serverName);
            fileInfo.put("position", tf.position);
            fileInfo.put("lineNumber", tf.lineNumber);
            fileInfo.put("charset", tf.encoding.label);
            fileInfo.put("currentSize", tf.lastSeenSize);
            fileInfo.put("exists", true);
            fileInfo.put("lastError", tf.lastError);
            trackedFilesList.add(fileInfo);
        }
        diagnostics.put("trackedFiles", trackedFilesList);

        return diagnostics;
    }
}
