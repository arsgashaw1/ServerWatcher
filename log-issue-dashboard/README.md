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
- Gradle 8.5+ (or use the included Gradle wrapper)

## Building

```bash
cd log-issue-dashboard
./gradlew build
```

This creates:
- A regular JAR file: `build/libs/log-issue-dashboard-1.0.0.jar`
- A fat JAR with all dependencies: `build/libs/log-issue-dashboard-1.0.0-all.jar`

### Other Gradle Tasks

```bash
# Clean build artifacts
./gradlew clean

# Run the application
./gradlew run --args="<config-folder>"

# Build only the fat JAR
./gradlew fatJar
```

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
  "servers": [
    {
      "serverName": "U3172QA",
      "path": "/a/b/c",
      "description": "QA Server logs"
    },
    {
      "serverName": "U3172PROD",
      "path": "/u/prod/logs",
      "description": "Production Server logs"
    },
    {
      "serverName": "U3172DEV",
      "path": "/u/dev/logs",
      "description": "Development Server logs"
    }
  ],
  "watchPaths": [],
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
| `servers` | Array of objects | **Recommended:** Server-based paths with server names |
| `servers[].serverName` | String | Name of the server (e.g., "U3172QA") |
| `servers[].path` | String | USS path to watch on this server |
| `servers[].description` | String | Optional description of the server |
| `watchPaths` | Array of strings | Legacy: Simple paths without server names |
| `filePatterns` | Array of strings | Glob patterns for files to monitor (e.g., `*.log`) |
| `exceptionPatterns` | Array of strings | Regex patterns to detect exceptions |
| `errorPatterns` | Array of strings | Regex patterns to detect errors |
| `warningPatterns` | Array of strings | Regex patterns to detect warnings |
| `pollingIntervalSeconds` | Integer | How often to check for file changes (seconds) |
| `maxIssuesDisplayed` | Integer | Maximum number of issues to keep in memory |
| `enableSound` | Boolean | Enable sound notifications (not yet implemented) |
| `windowTitle` | String | Title of the dashboard window |

### Server-Based Configuration

The `servers` array is the recommended way to configure watched paths. Each entry associates a server name with a path:

```json
{
  "servers": [
    {
      "serverName": "U3172QA",
      "path": "/a/b/c",
      "description": "QA Server logs"
    }
  ]
}
```

The server name will be displayed in a dedicated column in the dashboard, making it easy to identify which server each issue came from. Each server is color-coded for quick visual identification.

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
├── build.gradle.kts                  # Gradle build file
├── settings.gradle.kts               # Gradle settings
├── gradle.properties                 # Gradle properties
├── gradlew                           # Gradle wrapper (Unix)
├── gradlew.bat                       # Gradle wrapper (Windows)
├── gradle/wrapper/                   # Gradle wrapper files
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
