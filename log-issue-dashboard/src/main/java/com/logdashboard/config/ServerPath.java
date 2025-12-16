package com.logdashboard.config;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;

/**
 * Represents a server and its associated log path to watch.
 */
public class ServerPath {
    
    private String serverName;
    private String path;
    private String description;
    private String encoding;  // Character encoding (e.g., "UTF-8", "Cp1047" for EBCDIC)
    
    // Common EBCDIC code pages
    public static final String EBCDIC_US = "Cp037";      // US/Canada
    public static final String EBCDIC_INTL = "Cp500";    // International
    public static final String EBCDIC_UNIX = "Cp1047";   // Unix/Open Systems
    public static final String EBCDIC_LATIN1 = "Cp1148"; // Latin-1 with Euro
    
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
