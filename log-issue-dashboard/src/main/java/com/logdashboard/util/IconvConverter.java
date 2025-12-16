package com.logdashboard.util;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Utility class for encoding conversion using the system's iconv command.
 * 
 * This is particularly useful for IBM mainframe encodings (EBCDIC) where the
 * system's iconv may provide better compatibility than Java's built-in charset handling.
 * 
 * Common conversions:
 * - IBM-1047 (EBCDIC Unix/z/OS) -> UTF-8
 * - ISO8859-1 -> IBM-1047 (for writing EBCDIC)
 * - Cp037 (EBCDIC US/Canada) -> UTF-8
 * 
 * Usage:
 * <pre>
 *   IconvConverter.convertFileToUtf8(path, "IBM-1047");
 *   String text = IconvConverter.convertToUtf8(bytes, "IBM-1047");
 * </pre>
 */
public class IconvConverter {
    
    // iconv encoding names for IBM mainframe code pages
    public static final String ICONV_EBCDIC_1047 = "IBM-1047";    // z/OS Unix
    public static final String ICONV_EBCDIC_037 = "IBM-037";      // US/Canada
    public static final String ICONV_EBCDIC_500 = "IBM-500";      // International
    public static final String ICONV_EBCDIC_1148 = "IBM-1148";    // Latin-1 with Euro
    public static final String ICONV_ISO8859_1 = "ISO8859-1";     // Latin-1
    public static final String ICONV_UTF8 = "UTF-8";
    
    // Timeout for iconv process (seconds)
    private static final int ICONV_TIMEOUT_SECONDS = 30;
    
    // Cache iconv availability check
    private static Boolean iconvAvailable = null;
    
    /**
     * Checks if the iconv command is available on this system.
     */
    public static synchronized boolean isIconvAvailable() {
        if (iconvAvailable == null) {
            try {
                ProcessBuilder pb = new ProcessBuilder("iconv", "--version");
                pb.redirectErrorStream(true);
                Process process = pb.start();
                boolean completed = process.waitFor(5, TimeUnit.SECONDS);
                iconvAvailable = completed && process.exitValue() == 0;
            } catch (Exception e) {
                iconvAvailable = false;
            }
        }
        return iconvAvailable;
    }
    
    /**
     * Converts a file from a source encoding to UTF-8 using iconv.
     * 
     * @param inputPath Path to the input file
     * @param fromEncoding Source encoding (e.g., "IBM-1047")
     * @return The converted content as a UTF-8 string
     * @throws IOException if conversion fails
     */
    public static String convertFileToUtf8(Path inputPath, String fromEncoding) throws IOException {
        if (!isIconvAvailable()) {
            return convertFileToUtf8Fallback(inputPath, fromEncoding);
        }
        
        ProcessBuilder pb = new ProcessBuilder(
            "iconv",
            "-f", fromEncoding,
            "-t", ICONV_UTF8,
            inputPath.toString()
        );
        
        return executeIconvProcess(pb);
    }
    
    /**
     * Converts bytes from a source encoding to UTF-8 using iconv.
     * 
     * @param data The bytes to convert
     * @param fromEncoding Source encoding (e.g., "IBM-1047")
     * @return The converted content as a UTF-8 string
     * @throws IOException if conversion fails
     */
    public static String convertToUtf8(byte[] data, String fromEncoding) throws IOException {
        if (!isIconvAvailable()) {
            return convertToUtf8Fallback(data, fromEncoding);
        }
        
        ProcessBuilder pb = new ProcessBuilder(
            "iconv",
            "-f", fromEncoding,
            "-t", ICONV_UTF8
        );
        
        return executeIconvProcessWithInput(pb, data);
    }
    
    /**
     * Converts bytes from a source encoding to ISO8859-1 using iconv.
     * This is the preferred conversion for EBCDIC mainframe files.
     * Command: iconv -f IBM-1047 -t ISO8859-1
     * 
     * @param data The bytes to convert
     * @param fromEncoding Source encoding (e.g., "IBM-1047")
     * @return The converted content as an ISO8859-1 string
     * @throws IOException if conversion fails
     */
    public static String convertToIso8859(byte[] data, String fromEncoding) throws IOException {
        if (!isIconvAvailable()) {
            System.out.println("DEBUG: iconv not available, using fallback");
            return convertToIso8859Fallback(data, fromEncoding);
        }
        
        // TODO: REMOVE - Debug print actual command
        System.out.println("DEBUG: Running command: iconv -f " + fromEncoding + " -t " + ICONV_ISO8859_1);
        
        ProcessBuilder pb = new ProcessBuilder(
            "iconv",
            "-f", fromEncoding,
            "-t", ICONV_ISO8859_1
        );
        
        return executeIconvProcessWithInput(pb, data, StandardCharsets.ISO_8859_1);
    }
    
    /**
     * Converts EBCDIC bytes to readable text.
     * Uses: iconv -f [ebcdicEncoding] -t ISO8859-1
     * This is the correct conversion for IBM mainframe EBCDIC files.
     * 
     * @param data The EBCDIC bytes to convert
     * @param ebcdicEncoding Source EBCDIC encoding (e.g., "IBM-1047", "IBM-037")
     * @return The converted content as a readable string
     * @throws IOException if conversion fails
     */
    public static String convertEbcdicToReadable(byte[] data, String ebcdicEncoding) throws IOException {
        // Normalize encoding name to iconv format
        String iconvEncoding = normalizeToIconvEncoding(ebcdicEncoding);
        return convertToIso8859(data, iconvEncoding);
    }
    
    /**
     * Normalizes encoding name to iconv-compatible format.
     */
    public static String normalizeToIconvEncoding(String encoding) {
        if (encoding == null || encoding.isEmpty()) {
            return ICONV_UTF8;
        }
        
        String upper = encoding.toUpperCase().replace("_", "-").trim();
        
        // Already in iconv format
        if (upper.startsWith("IBM-")) {
            return upper;
        }
        
        // Map common names to iconv format
        switch (upper) {
            case "EBCDIC":
            case "EBCDIC-1047":
            case "EBCDIC-UNIX":
            case "CP1047":
            case "IBM1047":
                return ICONV_EBCDIC_1047;
            case "EBCDIC-037":
            case "EBCDIC-US":
            case "CP037":
            case "IBM037":
                return ICONV_EBCDIC_037;
            case "EBCDIC-500":
            case "EBCDIC-INTL":
            case "CP500":
            case "IBM500":
                return ICONV_EBCDIC_500;
            case "EBCDIC-1148":
            case "CP1148":
            case "IBM1148":
                return ICONV_EBCDIC_1148;
            case "ISO8859-1":
            case "ISO-8859-1":
            case "LATIN1":
            case "LATIN-1":
                return ICONV_ISO8859_1;
            case "UTF-8":
            case "UTF8":
                return ICONV_UTF8;
            default:
                // Try to convert CpXXXX to IBM-XXXX
                if (upper.startsWith("CP")) {
                    return "IBM-" + upper.substring(2);
                }
                return encoding;
        }
    }
    
    /**
     * Checks if the encoding is an EBCDIC/IBM mainframe encoding.
     */
    public static boolean isEbcdicEncoding(String encoding) {
        if (encoding == null || encoding.isEmpty()) {
            return false;
        }
        String upper = encoding.toUpperCase();
        return upper.contains("EBCDIC") || 
               upper.contains("IBM-10") || upper.contains("IBM10") ||
               upper.contains("IBM-03") || upper.contains("IBM03") ||
               upper.contains("IBM-5") || upper.contains("IBM5") ||
               upper.startsWith("CP037") || upper.equals("CP037") ||
               upper.startsWith("CP500") || upper.equals("CP500") ||
               upper.startsWith("CP1047") || upper.equals("CP1047") ||
               upper.startsWith("CP1148") || upper.equals("CP1148");
    }
    
    /**
     * Converts a file from a source encoding to ISO8859-1 using iconv.
     * This is the preferred conversion for EBCDIC mainframe files.
     * 
     * @param inputPath Path to the input file
     * @param fromEncoding Source encoding (e.g., "IBM-1047")
     * @return The converted content as an ISO8859-1 string
     * @throws IOException if conversion fails
     */
    public static String convertFileToIso8859(Path inputPath, String fromEncoding) throws IOException {
        if (!isIconvAvailable()) {
            return convertFileToIso8859Fallback(inputPath, fromEncoding);
        }
        
        ProcessBuilder pb = new ProcessBuilder(
            "iconv",
            "-f", fromEncoding,
            "-t", ICONV_ISO8859_1,
            inputPath.toString()
        );
        
        return executeIconvProcess(pb, StandardCharsets.ISO_8859_1);
    }
    
    /**
     * Converts a UTF-8 string to IBM-1047 (EBCDIC) encoding.
     * This is useful for writing EBCDIC files.
     * 
     * @param text The UTF-8 text to convert
     * @return The bytes in IBM-1047 encoding
     * @throws IOException if conversion fails
     */
    public static byte[] convertUtf8ToEbcdic(String text) throws IOException {
        return convertUtf8ToEbcdic(text, ICONV_EBCDIC_1047);
    }
    
    /**
     * Converts a UTF-8 string to a specified EBCDIC encoding.
     * 
     * @param text The UTF-8 text to convert
     * @param toEncoding Target EBCDIC encoding (e.g., "IBM-1047")
     * @return The bytes in the target encoding
     * @throws IOException if conversion fails
     */
    public static byte[] convertUtf8ToEbcdic(String text, String toEncoding) throws IOException {
        if (!isIconvAvailable()) {
            return convertUtf8ToEbcdicFallback(text, toEncoding);
        }
        
        ProcessBuilder pb = new ProcessBuilder(
            "iconv",
            "-f", ICONV_UTF8,
            "-t", toEncoding
        );
        
        return executeIconvProcessWithInputGetBytes(pb, text.getBytes(StandardCharsets.UTF_8));
    }
    
    /**
     * Converts from ISO8859-1 to IBM-1047 (for mainframe compatibility).
     * This matches the command: iconv -f ISO8859-1 -t IBM-1047
     * 
     * @param data The ISO8859-1 bytes
     * @return The bytes in IBM-1047 encoding
     * @throws IOException if conversion fails
     */
    public static byte[] convertIso8859ToEbcdic(byte[] data) throws IOException {
        if (!isIconvAvailable()) {
            return convertIso8859ToEbcdicFallback(data);
        }
        
        ProcessBuilder pb = new ProcessBuilder(
            "iconv",
            "-f", ICONV_ISO8859_1,
            "-t", ICONV_EBCDIC_1047
        );
        
        return executeIconvProcessWithInputGetBytes(pb, data);
    }
    
    /**
     * Reads an EBCDIC file and returns lines as UTF-8 strings.
     * 
     * @param filePath Path to the EBCDIC file
     * @param ebcdicEncoding EBCDIC encoding name (e.g., "IBM-1047")
     * @return Array of lines in UTF-8
     * @throws IOException if reading fails
     */
    public static String[] readEbcdicFileLines(Path filePath, String ebcdicEncoding) throws IOException {
        String content = convertFileToUtf8(filePath, ebcdicEncoding);
        return content.split("\\r?\\n");
    }
    
    /**
     * Reads a portion of an EBCDIC file (for tailing).
     * 
     * @param filePath Path to the EBCDIC file
     * @param startPosition Starting byte position
     * @param ebcdicEncoding EBCDIC encoding name
     * @return The content as UTF-8 string
     * @throws IOException if reading fails
     */
    public static String readEbcdicFilePortion(Path filePath, long startPosition, String ebcdicEncoding) 
            throws IOException {
        // Read bytes from the position
        byte[] allBytes = Files.readAllBytes(filePath);
        if (startPosition >= allBytes.length) {
            return "";
        }
        
        int length = (int) (allBytes.length - startPosition);
        byte[] portion = new byte[length];
        System.arraycopy(allBytes, (int) startPosition, portion, 0, length);
        
        return convertToUtf8(portion, ebcdicEncoding);
    }
    
    /**
     * Maps Java charset names to iconv encoding names.
     */
    public static String javaCharsetToIconvEncoding(Charset charset) {
        String name = charset.name().toUpperCase();
        
        switch (name) {
            case "CP1047":
            case "IBM1047":
                return ICONV_EBCDIC_1047;
            case "CP037":
            case "IBM037":
                return ICONV_EBCDIC_037;
            case "CP500":
            case "IBM500":
                return ICONV_EBCDIC_500;
            case "CP1148":
            case "IBM1148":
                return ICONV_EBCDIC_1148;
            case "ISO-8859-1":
            case "ISO8859-1":
            case "LATIN1":
                return ICONV_ISO8859_1;
            case "UTF-8":
                return ICONV_UTF8;
            default:
                // Return as-is, iconv might understand it
                return name;
        }
    }
    
    /**
     * Maps iconv encoding names to Java Charset.
     */
    public static Charset iconvEncodingToJavaCharset(String iconvEncoding) {
        String upper = iconvEncoding.toUpperCase().replace("-", "").replace("_", "");
        
        switch (upper) {
            case "IBM1047":
            case "EBCDIC1047":
                return Charset.forName("Cp1047");
            case "IBM037":
            case "EBCDIC037":
                return Charset.forName("Cp037");
            case "IBM500":
            case "EBCDIC500":
                return Charset.forName("Cp500");
            case "IBM1148":
            case "EBCDIC1148":
                return Charset.forName("Cp1148");
            case "ISO88591":
            case "LATIN1":
                return StandardCharsets.ISO_8859_1;
            case "UTF8":
                return StandardCharsets.UTF_8;
            default:
                try {
                    return Charset.forName(iconvEncoding);
                } catch (Exception e) {
                    return StandardCharsets.UTF_8;
                }
        }
    }
    
    // === Private helper methods ===
    
    private static String executeIconvProcess(ProcessBuilder pb) throws IOException {
        return executeIconvProcess(pb, StandardCharsets.UTF_8);
    }
    
    private static String executeIconvProcess(ProcessBuilder pb, Charset outputCharset) throws IOException {
        pb.redirectErrorStream(true);
        Process process = pb.start();
        
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), outputCharset))) {
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() > 0) {
                    output.append("\n");
                }
                output.append(line);
            }
            
            boolean completed = process.waitFor(ICONV_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new IOException("iconv process timed out");
            }
            
            if (process.exitValue() != 0) {
                throw new IOException("iconv failed with exit code: " + process.exitValue());
            }
            
            return output.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("iconv process interrupted", e);
        }
    }
    
    private static String executeIconvProcessWithInput(ProcessBuilder pb, byte[] input) throws IOException {
        return executeIconvProcessWithInput(pb, input, StandardCharsets.UTF_8);
    }
    
    private static String executeIconvProcessWithInput(ProcessBuilder pb, byte[] input, Charset outputCharset) throws IOException {
        pb.redirectErrorStream(true);
        Process process = pb.start();
        
        // Write input to process stdin
        try (OutputStream os = process.getOutputStream()) {
            os.write(input);
            os.flush();
        }
        
        // Read output
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), outputCharset))) {
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() > 0) {
                    output.append("\n");
                }
                output.append(line);
            }
            
            boolean completed = process.waitFor(ICONV_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new IOException("iconv process timed out");
            }
            
            if (process.exitValue() != 0) {
                throw new IOException("iconv failed with exit code: " + process.exitValue());
            }
            
            return output.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("iconv process interrupted", e);
        }
    }
    
    private static byte[] executeIconvProcessWithInputGetBytes(ProcessBuilder pb, byte[] input) throws IOException {
        pb.redirectErrorStream(true);
        Process process = pb.start();
        
        // Write input to process stdin
        try (OutputStream os = process.getOutputStream()) {
            os.write(input);
            os.flush();
        }
        
        // Read output as bytes
        try (InputStream is = process.getInputStream();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            
            boolean completed = process.waitFor(ICONV_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new IOException("iconv process timed out");
            }
            
            if (process.exitValue() != 0) {
                throw new IOException("iconv failed with exit code: " + process.exitValue());
            }
            
            return baos.toByteArray();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("iconv process interrupted", e);
        }
    }
    
    // === Fallback methods using Java's built-in charset handling ===
    
    private static String convertFileToUtf8Fallback(Path inputPath, String fromEncoding) throws IOException {
        Charset charset = iconvEncodingToJavaCharset(fromEncoding);
        return new String(Files.readAllBytes(inputPath), charset);
    }
    
    private static String convertToUtf8Fallback(byte[] data, String fromEncoding) {
        Charset charset = iconvEncodingToJavaCharset(fromEncoding);
        return new String(data, charset);
    }
    
    private static String convertToIso8859Fallback(byte[] data, String fromEncoding) {
        // First decode from source encoding, then the string is in Java's internal UTF-16
        // When we return it as ISO-8859-1 string, the caller should handle it correctly
        Charset charset = iconvEncodingToJavaCharset(fromEncoding);
        String text = new String(data, charset);
        return text;
    }
    
    private static String convertFileToIso8859Fallback(Path inputPath, String fromEncoding) throws IOException {
        Charset charset = iconvEncodingToJavaCharset(fromEncoding);
        return new String(Files.readAllBytes(inputPath), charset);
    }
    
    private static byte[] convertUtf8ToEbcdicFallback(String text, String toEncoding) {
        Charset charset = iconvEncodingToJavaCharset(toEncoding);
        return text.getBytes(charset);
    }
    
    private static byte[] convertIso8859ToEbcdicFallback(byte[] data) {
        // Convert ISO-8859-1 bytes to String, then to EBCDIC
        String text = new String(data, StandardCharsets.ISO_8859_1);
        return text.getBytes(Charset.forName("Cp1047"));
    }
}
