package com.logdashboard.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Utility class to detect file encoding, with special support for EBCDIC detection.
 * 
 * EBCDIC (Extended Binary Coded Decimal Interchange Code) is used on IBM mainframes.
 * This detector uses heuristics to distinguish between EBCDIC and ASCII/UTF-8 encodings.
 */
public class EncodingDetector {
    
    // Common EBCDIC code pages
    public static final String EBCDIC_US = "Cp037";      // US/Canada
    public static final String EBCDIC_INTL = "Cp500";    // International
    public static final String EBCDIC_UNIX = "Cp1047";   // Unix/Open Systems (z/OS)
    public static final String EBCDIC_LATIN1 = "Cp1148"; // Latin-1 with Euro
    
    // Default EBCDIC to use when detected (Cp1047 is most common for z/OS Unix)
    public static final String DEFAULT_EBCDIC = EBCDIC_UNIX;
    
    // Number of bytes to sample for detection
    private static final int SAMPLE_SIZE = 4096;
    
    // EBCDIC byte values for common characters
    private static final byte EBCDIC_SPACE = 0x40;
    private static final byte EBCDIC_NEWLINE = 0x15;      // NL (New Line) in EBCDIC
    private static final byte EBCDIC_NEWLINE_ALT = 0x25;  // LF in some EBCDIC variants
    private static final byte EBCDIC_CARRIAGE_RETURN = 0x0D;
    
    // ASCII/UTF-8 byte values
    private static final byte ASCII_SPACE = 0x20;
    private static final byte ASCII_NEWLINE = 0x0A;
    
    /**
     * Detects the encoding of a file.
     * 
     * @param filePath Path to the file to analyze
     * @return The detected Charset, defaults to UTF-8 if detection fails
     */
    public static Charset detectEncoding(Path filePath) {
        return detectEncoding(filePath, null);
    }
    
    /**
     * Detects the encoding of a file, with a status callback for logging.
     * 
     * @param filePath Path to the file to analyze
     * @param statusCallback Optional callback for status messages
     * @return The detected Charset
     */
    public static Charset detectEncoding(Path filePath, java.util.function.Consumer<String> statusCallback) {
        try {
            byte[] sample = readSample(filePath);
            if (sample.length == 0) {
                return StandardCharsets.UTF_8;
            }
            
            EncodingResult result = analyzeBytes(sample);
            
            if (statusCallback != null) {
                statusCallback.accept(String.format(
                    "Encoding detection for %s: %s (confidence: %.1f%%, EBCDIC score: %.1f%%, ASCII score: %.1f%%)",
                    filePath.getFileName(),
                    result.charset.displayName(),
                    result.confidence * 100,
                    result.ebcdicScore * 100,
                    result.asciiScore * 100
                ));
            }
            
            return result.charset;
            
        } catch (IOException e) {
            if (statusCallback != null) {
                statusCallback.accept("Encoding detection failed for " + filePath + ": " + e.getMessage());
            }
            return StandardCharsets.UTF_8;
        }
    }
    
    /**
     * Detects encoding and returns detailed result.
     */
    public static EncodingResult detectEncodingWithDetails(Path filePath) throws IOException {
        byte[] sample = readSample(filePath);
        if (sample.length == 0) {
            return new EncodingResult(StandardCharsets.UTF_8, 1.0, 0, 1.0);
        }
        return analyzeBytes(sample);
    }
    
    /**
     * Reads a sample of bytes from the file for analysis.
     */
    private static byte[] readSample(Path filePath) throws IOException {
        try (InputStream is = Files.newInputStream(filePath)) {
            byte[] buffer = new byte[SAMPLE_SIZE];
            int bytesRead = is.read(buffer);
            if (bytesRead <= 0) {
                return new byte[0];
            }
            if (bytesRead < buffer.length) {
                byte[] result = new byte[bytesRead];
                System.arraycopy(buffer, 0, result, 0, bytesRead);
                return result;
            }
            return buffer;
        }
    }
    
    /**
     * Analyzes byte array to determine encoding.
     * 
     * Heuristics used:
     * 1. Check for UTF-8 BOM
     * 2. Count EBCDIC-specific patterns (0x40 for space, specific letter ranges)
     * 3. Count ASCII-specific patterns (0x20 for space, printable range 0x20-0x7E)
     * 4. Check for valid UTF-8 sequences
     * 5. Analyze character frequency distribution
     */
    private static EncodingResult analyzeBytes(byte[] bytes) {
        // Check for UTF-8 BOM
        if (bytes.length >= 3 && 
            (bytes[0] & 0xFF) == 0xEF && 
            (bytes[1] & 0xFF) == 0xBB && 
            (bytes[2] & 0xFF) == 0xBF) {
            return new EncodingResult(StandardCharsets.UTF_8, 1.0, 0, 1.0);
        }
        
        int ebcdicIndicators = 0;
        int asciiIndicators = 0;
        int totalPrintable = 0;
        int ebcdicPrintable = 0;
        int asciiPrintable = 0;
        int ebcdicSpaces = 0;
        int asciiSpaces = 0;
        int ebcdicNewlines = 0;
        int asciiNewlines = 0;
        
        for (byte b : bytes) {
            int unsigned = b & 0xFF;
            
            // Count spaces
            if (b == EBCDIC_SPACE) {
                ebcdicSpaces++;
            }
            if (b == ASCII_SPACE) {
                asciiSpaces++;
            }
            
            // Count newlines
            if (b == EBCDIC_NEWLINE || b == EBCDIC_NEWLINE_ALT) {
                ebcdicNewlines++;
            }
            if (b == ASCII_NEWLINE) {
                asciiNewlines++;
            }
            
            // EBCDIC printable range analysis
            // EBCDIC lowercase: 0x81-0x89, 0x91-0x99, 0xA2-0xA9
            // EBCDIC uppercase: 0xC1-0xC9, 0xD1-0xD9, 0xE2-0xE9
            // EBCDIC digits: 0xF0-0xF9
            if (isEbcdicPrintable(unsigned)) {
                ebcdicPrintable++;
            }
            
            // ASCII printable range: 0x20-0x7E
            if (unsigned >= 0x20 && unsigned <= 0x7E) {
                asciiPrintable++;
            }
            
            // EBCDIC-specific indicators (bytes common in EBCDIC but rare/invalid in ASCII text)
            // High bytes (0x80+) that form EBCDIC letters
            if ((unsigned >= 0xC1 && unsigned <= 0xC9) ||  // A-I
                (unsigned >= 0xD1 && unsigned <= 0xD9) ||  // J-R
                (unsigned >= 0xE2 && unsigned <= 0xE9) ||  // S-Z
                (unsigned >= 0x81 && unsigned <= 0x89) ||  // a-i
                (unsigned >= 0x91 && unsigned <= 0x99) ||  // j-r
                (unsigned >= 0xA2 && unsigned <= 0xA9) ||  // s-z
                (unsigned >= 0xF0 && unsigned <= 0xF9)) {  // 0-9
                ebcdicIndicators++;
            }
            
            // ASCII letter/digit indicators
            if ((unsigned >= 0x41 && unsigned <= 0x5A) ||  // A-Z
                (unsigned >= 0x61 && unsigned <= 0x7A) ||  // a-z
                (unsigned >= 0x30 && unsigned <= 0x39)) {  // 0-9
                asciiIndicators++;
            }
            
            totalPrintable++;
        }
        
        // Calculate scores
        double ebcdicScore = 0;
        double asciiScore = 0;
        
        if (bytes.length > 0) {
            // Weight different factors
            double ebcdicCharScore = (double) ebcdicIndicators / bytes.length;
            double asciiCharScore = (double) asciiIndicators / bytes.length;
            double ebcdicPrintableRatio = (double) ebcdicPrintable / bytes.length;
            double asciiPrintableRatio = (double) asciiPrintable / bytes.length;
            
            // Space analysis is important - 0x40 is very common in EBCDIC text
            double ebcdicSpaceScore = (double) ebcdicSpaces / Math.max(1, bytes.length / 10);
            double asciiSpaceScore = (double) asciiSpaces / Math.max(1, bytes.length / 10);
            
            // Combine scores with weights
            ebcdicScore = (ebcdicCharScore * 0.4) + (ebcdicPrintableRatio * 0.3) + 
                         (Math.min(1.0, ebcdicSpaceScore) * 0.2) + 
                         (ebcdicNewlines > 0 ? 0.1 : 0);
            
            asciiScore = (asciiCharScore * 0.4) + (asciiPrintableRatio * 0.3) + 
                        (Math.min(1.0, asciiSpaceScore) * 0.2) + 
                        (asciiNewlines > 0 ? 0.1 : 0);
        }
        
        // Determine encoding based on scores
        if (ebcdicScore > asciiScore && ebcdicScore > 0.3) {
            double confidence = ebcdicScore / (ebcdicScore + asciiScore + 0.01);
            return new EncodingResult(
                Charset.forName(DEFAULT_EBCDIC), 
                confidence,
                ebcdicScore,
                asciiScore
            );
        } else {
            double confidence = asciiScore / (ebcdicScore + asciiScore + 0.01);
            return new EncodingResult(
                StandardCharsets.UTF_8, 
                Math.max(0.5, confidence),
                ebcdicScore,
                asciiScore
            );
        }
    }
    
    /**
     * Checks if a byte value is a printable character in EBCDIC.
     */
    private static boolean isEbcdicPrintable(int b) {
        // Space
        if (b == 0x40) return true;
        // Lowercase letters (a-i, j-r, s-z)
        if ((b >= 0x81 && b <= 0x89) || (b >= 0x91 && b <= 0x99) || (b >= 0xA2 && b <= 0xA9)) return true;
        // Uppercase letters (A-I, J-R, S-Z)
        if ((b >= 0xC1 && b <= 0xC9) || (b >= 0xD1 && b <= 0xD9) || (b >= 0xE2 && b <= 0xE9)) return true;
        // Digits (0-9)
        if (b >= 0xF0 && b <= 0xF9) return true;
        // Common punctuation
        if (b == 0x4B || b == 0x4C || b == 0x4D || b == 0x4E || b == 0x5A || b == 0x5B) return true;
        // Special characters
        if (b == 0x6B || b == 0x6C || b == 0x7A || b == 0x7B || b == 0x7C || b == 0x7D) return true;
        return false;
    }
    
    /**
     * Result of encoding detection with confidence score.
     */
    public static class EncodingResult {
        public final Charset charset;
        public final double confidence;  // 0.0 to 1.0
        public final double ebcdicScore;
        public final double asciiScore;
        
        public EncodingResult(Charset charset, double confidence, double ebcdicScore, double asciiScore) {
            this.charset = charset;
            this.confidence = confidence;
            this.ebcdicScore = ebcdicScore;
            this.asciiScore = asciiScore;
        }
        
        public boolean isEbcdic() {
            return charset.name().startsWith("Cp") || 
                   charset.name().contains("EBCDIC") ||
                   charset.name().startsWith("IBM");
        }
        
        @Override
        public String toString() {
            return String.format("EncodingResult[charset=%s, confidence=%.2f, ebcdic=%.2f, ascii=%.2f]",
                charset.displayName(), confidence, ebcdicScore, asciiScore);
        }
    }
    
    /**
     * Quick check if a file appears to be EBCDIC encoded.
     */
    public static boolean isLikelyEbcdic(Path filePath) {
        try {
            EncodingResult result = detectEncodingWithDetails(filePath);
            return result.isEbcdic();
        } catch (IOException e) {
            return false;
        }
    }
}
