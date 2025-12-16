# Log Issue Dashboard

A Java application that watches log files for exceptions, errors, and warnings, and displays them in a real-time dashboard UI.

## Features

- **Real-time Monitoring**: Watches specified directories for log file changes
- **Pattern Detection**: Detects exceptions, errors, and warnings using configurable regex patterns
- **Dashboard UI**: Modern Swing-based UI showing all detected issues
- **Stack Trace Display**: Full stack trace viewing for exceptions
- **Filtering**: Filter by severity (Error, Warning, Exception) and search text
- **Sortable Table**: Sort issues by time, severity, type, file, or message
- **Configurable**: JSON-based configuration for all settings
- **File Rotation Support**: Handles log file rotation gracefully

## Requirements

- Java 11 or higher
- Maven 3.6+ (for building)

## Building

```bash
cd log-issue-dashboard
mvn clean package
```

This creates an executable JAR file: `target/log-issue-dashboard-1.0.0.jar`

## Usage

### Basic Usage

```bash
java -jar log-issue-dashboard-1.0.0.jar <config-folder>
```

Where `<config-folder>` is the path to a folder containing the `dashboard-config.json` configuration file.

### Examples

```bash
# Use configuration from ./config folder
java -jar log-issue-dashboard-1.0.0.jar ./config

# Use configuration from absolute path
java -jar log-issue-dashboard-1.0.0.jar /home/user/myapp/config

# Show help
java -jar log-issue-dashboard-1.0.0.jar --help

# Show version
java -jar log-issue-dashboard-1.0.0.jar --version

# Create sample configuration
java -jar log-issue-dashboard-1.0.0.jar --create-config /path/to/output
```

## Configuration

The application reads configuration from a `dashboard-config.json` file in the specified config folder.

### Sample Configuration

```json
{
  "watchPaths": [
    "/path/to/your/logs",
    "/another/log/directory"
  ],
  "filePatterns": [
    "*.log",
    "*.txt",
    "*.out"
  ],
  "exceptionPatterns": [
    ".*Exception.*",
    ".*Error:.*",
    ".*at\\s+[\\w.$]+\\([^)]+\\).*",
    ".*Caused by:.*",
    ".*FATAL.*",
    ".*Throwable.*"
  ],
  "errorPatterns": [
    ".*\\bERROR\\b.*",
    ".*\\bFAILED\\b.*",
    ".*\\bFAILURE\\b.*"
  ],
  "warningPatterns": [
    ".*\\bWARN\\b.*",
    ".*\\bWARNING\\b.*"
  ],
  "pollingIntervalSeconds": 2,
  "maxIssuesDisplayed": 500,
  "enableSound": false,
  "windowTitle": "Log Issue Dashboard"
}
```

### Configuration Options

| Option | Type | Description |
|--------|------|-------------|
| `watchPaths` | Array of strings | Directories or files to watch for changes |
| `filePatterns` | Array of strings | Glob patterns for files to monitor (e.g., `*.log`) |
| `exceptionPatterns` | Array of strings | Regex patterns to detect exceptions |
| `errorPatterns` | Array of strings | Regex patterns to detect errors |
| `warningPatterns` | Array of strings | Regex patterns to detect warnings |
| `pollingIntervalSeconds` | Integer | How often to check for file changes (seconds) |
| `maxIssuesDisplayed` | Integer | Maximum number of issues to keep in memory |
| `enableSound` | Boolean | Enable sound notifications (not yet implemented) |
| `windowTitle` | String | Title of the dashboard window |

## Dashboard Features

### Issue Table
- Displays all detected issues with: Time, Severity, Type, File, Line, Message
- Click on a row to view full details and stack trace
- Double-click to acknowledge an issue
- Sortable by clicking column headers

### Filtering
- **Checkboxes**: Show/hide Errors, Warnings, Exceptions
- **Search**: Real-time text search across all columns

### Actions
- **Clear All**: Remove all issues from the dashboard
- **Refresh**: Refresh the current view
- **Acknowledge Selected**: Mark an issue as acknowledged

### Details Panel
- Shows full stack trace for the selected issue
- Displays all metadata (time, file, line number, etc.)

## USS Path Support

This application works well with USS (Unix System Services) paths on z/OS:

```json
{
  "watchPaths": [
    "/u/userid/logs",
    "/var/log/myapp"
  ]
}
```

## Troubleshooting

### No Issues Appearing
1. Check that `watchPaths` points to valid directories containing log files
2. Verify `filePatterns` match your log file names
3. Ensure the patterns in `exceptionPatterns`, `errorPatterns`, or `warningPatterns` match your log format

### High CPU Usage
- Increase `pollingIntervalSeconds` to reduce how often files are checked

### Memory Issues
- Reduce `maxIssuesDisplayed` to limit memory usage

## Project Structure

```
log-issue-dashboard/
├── pom.xml
├── README.md
├── config-sample/
│   └── dashboard-config.json
└── src/main/java/com/logdashboard/
    ├── LogDashboardApp.java          # Main entry point
    ├── config/
    │   ├── ConfigLoader.java         # Configuration loading
    │   └── DashboardConfig.java      # Configuration model
    ├── model/
    │   └── LogIssue.java             # Issue data model
    ├── parser/
    │   └── LogParser.java            # Log parsing logic
    ├── ui/
    │   └── DashboardFrame.java       # Main UI
    └── watcher/
        └── LogFileWatcher.java       # File watching service
```

## License

This project is provided as-is for educational and practical use.
