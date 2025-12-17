package com.logdashboard.config;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents a server and its associated log path to watch.
 */
public class ServerPath {
    
    private String serverName;
    private String path;
    private String description;
    private String encoding;  // Default encoding for files (e.g., "UTF-8", "Cp1047" for EBCDIC)
    private boolean useIconv; // Whether to use external iconv command for encoding conversion
    
    // Per-file encoding overrides: filename pattern -> encoding
    // Example: {"mat-extract.log": "IBM-1047", "application-server.log": "ISO8859-1"}
    private Map<String, String> fileEncodings;
    
    // Common EBCDIC code pages (Java charset names)
    public static final String EBCDIC_US = "Cp037";      // US/Canada
    public static final String EBCDIC_INTL = "Cp500";    // International
    public static final String EBCDIC_UNIX = "Cp1047";   // Unix/Open Systems
    public static final String EBCDIC_LATIN1 = "Cp1148"; // Latin-1 with Euro
    
    // iconv encoding names for IBM mainframe code pages
    public static final String ICONV_EBCDIC_1047 = "IBM-1047";    // z/OS Unix
    public static final String ICONV_EBCDIC_037 = "IBM-037";      // US/Canada
    public static final String ICONV_EBCDIC_500 = "IBM-500";      // International
    public static final String ICONV_EBCDIC_1148 = "IBM-1148";    // Latin-1 with Euro
    
    public ServerPath() {
        // Default constructor for JSON deserialization
    }
    
    public ServerPath(String serverName, String path) {
        this.serverName = serverName;
        this.path = path;
    }
    
    public ServerPath(String serverName, String path, String description) {
        this.serverName = serverName;
        this.path = path;
        this.description = description;
    }
    
    public ServerPath(String serverName, String path, String description, String encoding) {
        this.serverName = serverName;
        this.path = path;
        this.description = description;
        this.encoding = encoding;
        this.useIconv = false;
    }
    
    public ServerPath(String serverName, String path, String description, String encoding, boolean useIconv) {
        this.serverName = serverName;
        this.path = path;
        this.description = description;
        this.encoding = encoding;
        this.useIconv = useIconv;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
    
    public String getEncoding() {
        return encoding;
    }
    
    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }
    
    public boolean isUseIconv() {
        return useIconv;
    }
    
    public void setUseIconv(boolean useIconv) {
        this.useIconv = useIconv;
    }
    
    public Map<String, String> getFileEncodings() {
        return fileEncodings;
    }
    
    public void setFileEncodings(Map<String, String> fileEncodings) {
        this.fileEncodings = fileEncodings;
    }
    
    /**
     * Gets the encoding for a specific file.
     * First checks fileEncodings map for a matching pattern, then falls back to default encoding.
     * 
     * @param fileName The name of the file
     * @return The encoding for this file
     */
    public String getEncodingForFile(String fileName) {
        if (fileEncodings != null && fileName != null) {
            // Check for exact match first
            if (fileEncodings.containsKey(fileName)) {
                return fileEncodings.get(fileName);
            }
            // Check for pattern match (simple wildcard support)
            for (Map.Entry<String, String> entry : fileEncodings.entrySet()) {
                String pattern = entry.getKey();
                if (pattern.contains("*")) {
                    String regex = pattern.replace(".", "\\.").replace("*", ".*");
                    if (fileName.matches(regex)) {
                        return entry.getValue();
                    }
                }
            }
        }
        // Fall back to default encoding
        return encoding;
    }
    
    /**
     * Checks if the given encoding is EBCDIC/IBM mainframe encoding.
     */
    public static boolean isEbcdicEncoding(String enc) {
        if (enc == null || enc.isEmpty()) {
            return false;
        }
        String upper = enc.toUpperCase();
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
     * Returns the iconv-compatible encoding name for this server's encoding.
     * Maps Java charset names to iconv encoding names.
     * 
     * @return the iconv encoding name (e.g., "IBM-1047" for z/OS EBCDIC)
     */
    public String getIconvEncoding() {
        if (encoding == null || encoding.isEmpty()) {
            return "UTF-8";
        }
        
        String normalizedEncoding = encoding.toUpperCase().trim();
        switch (normalizedEncoding) {
            case "EBCDIC":
            case "EBCDIC-US":
            case "EBCDIC-037":
            case "CP037":
            case "IBM037":
                return ICONV_EBCDIC_037;
            case "EBCDIC-INTL":
            case "EBCDIC-500":
            case "CP500":
            case "IBM500":
                return ICONV_EBCDIC_500;
            case "EBCDIC-UNIX":
            case "EBCDIC-1047":
            case "CP1047":
            case "IBM1047":
                return ICONV_EBCDIC_1047;
            case "EBCDIC-LATIN1":
            case "EBCDIC-1148":
            case "CP1148":
            case "IBM1148":
                return ICONV_EBCDIC_1148;
            case "ISO8859-1":
            case "ISO-8859-1":
            case "LATIN1":
                return "ISO8859-1";
            case "UTF-8":
            case "UTF8":
                return "UTF-8";
            default:
                // For iconv, IBM-XXXX format is typically used for EBCDIC
                if (normalizedEncoding.startsWith("CP")) {
                    return "IBM-" + normalizedEncoding.substring(2);
                }
                return encoding;
        }
    }
    
    /**
     * Returns the Charset for this server's log files.
     * Supports EBCDIC and various other encodings.
     * 
     * @return the Charset to use for reading log files
     */
    public Charset getCharset() {
        if (encoding == null || encoding.isEmpty()) {
            return StandardCharsets.UTF_8;
        }
        
        // Handle common EBCDIC aliases
        String normalizedEncoding = encoding.toUpperCase().trim();
        switch (normalizedEncoding) {
            case "EBCDIC":
            case "EBCDIC-US":
            case "EBCDIC-037":
                return Charset.forName(EBCDIC_US);
            case "EBCDIC-INTL":
            case "EBCDIC-500":
                return Charset.forName(EBCDIC_INTL);
            case "EBCDIC-UNIX":
            case "EBCDIC-1047":
                return Charset.forName(EBCDIC_UNIX);
            case "EBCDIC-LATIN1":
            case "EBCDIC-1148":
                return Charset.forName(EBCDIC_LATIN1);
            default:
                try {
                    return Charset.forName(encoding);
                } catch (UnsupportedCharsetException e) {
                    System.err.println("Warning: Unsupported encoding '" + encoding + 
                                     "', falling back to UTF-8");
                    return StandardCharsets.UTF_8;
                }
        }
    }
    
    /**
     * Checks if this server uses EBCDIC encoding.
     */
    public boolean isEbcdic() {
        if (encoding == null || encoding.isEmpty()) {
            return false;
        }
        String upper = encoding.toUpperCase();
        return upper.contains("EBCDIC") || 
               upper.startsWith("CP037") || upper.equals("CP037") ||
               upper.startsWith("CP500") || upper.equals("CP500") ||
               upper.startsWith("CP1047") || upper.equals("CP1047") ||
               upper.startsWith("CP1148") || upper.equals("CP1148") ||
               upper.startsWith("IBM037") || upper.startsWith("IBM500") ||
               upper.startsWith("IBM1047");
    }

    @Override
    public String toString() {
        String enc = encoding != null ? " [" + encoding + "]" : "";
        return serverName + " -> " + path + enc;
    }
}
