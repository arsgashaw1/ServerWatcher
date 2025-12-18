# Sentinel

**Enterprise-grade log monitoring and anomaly detection platform.**

A modern web-based dashboard for real-time log file monitoring with advanced analysis features. Built with embedded Tomcat and a beautiful, responsive UI.

![Sentinel Dashboard](docs/dashboard-preview.png)

## Features

### Real-time Monitoring
- **Live Issue Feed**: Watch exceptions, errors, and warnings appear in real-time via Server-Sent Events (SSE)
- **Multi-server Support**: Monitor log files from multiple servers simultaneously
- **File Pattern Matching**: Configure which log files to watch (*.log, *.txt, *.out, etc.)
- **Hot Reload**: Configuration changes are automatically detected and applied

### Modern Web UI
- **Beautiful Dashboard**: Clean, modern interface with dark/light theme support
- **Responsive Design**: Works on desktop, tablet, and mobile devices
- **Real-time Charts**: Visualize issue trends, severity distribution, and server breakdown
- **Issue Details Modal**: Click any issue to see full stack trace and details

### Persistent Storage (H2 Database)
- **Data Persistence**: Issues survive application restarts with H2 file-based database
- **Indexed Queries**: Fast lookups and filtering via database indexes
- **Higher Capacity**: Support for up to 100,000 issues (vs 50,000 in-memory)
- **Automatic Migration**: Schema versioning for future upgrades
- **Optional In-Memory Mode**: Fall back to in-memory storage if needed

### Analysis & Insights
- **Trend Analysis**: Track issue rate over time (minute-by-minute and hourly views)
- **Severity Distribution**: See breakdown of Critical, Exception, Error, and Warning issues
- **Server Health Scores**: Quick overview of which servers have the most issues
- **Recurring Issue Detection**: Automatically identifies repeated problems
- **Anomaly Detection**: Alerts for spikes, new exception types, and server concentration
- **Root Cause Candidates**: Identifies potential hotspots in your code

### Advanced Filtering & Export
- **Date Range Filtering**: Filter issues by date range to manage growing logs
- **Server Filtering**: View issues from specific servers only
- **Combined Filters**: Apply multiple filters simultaneously (date + server + severity)
- **Pagination**: Navigate through large result sets efficiently
- **Export**: Download filtered issues as JSON or CSV

### REST API
Full API access for integration with other tools:
- `GET /api/issues` - List all issues with pagination and filtering
- `GET /api/issues?from=2024-01-01&to=2024-01-31` - Filter by date range
- `GET /api/issues?server=Production&severity=ERROR` - Filter by server and severity
- `GET /api/issues/recent` - Get issues from the last N minutes
- `GET /api/stats/dashboard` - Comprehensive dashboard statistics
- `GET /api/analysis` - Detailed analysis report
- `GET /api/analysis/anomalies` - Detected anomalies
- `GET /api/daterange` - Get available date range for filtering
- `GET /api/export?format=csv` - Export filtered issues as CSV
- `POST /api/issues/{id}/acknowledge` - Acknowledge an issue
- `POST /api/issues/clear` - Clear all issues

## Quick Start

### Prerequisites
- Java 11 or higher
- Log files to monitor

### Installation

1. Download the latest release or build from source:

```bash
./gradlew build
```

2. Create a configuration folder:

```bash
mkdir config
java -jar build/libs/log-issue-dashboard-1.0.0-all.jar --create-config ./config
```

3. Edit the configuration file (`config/dashboard-config.json`):

```json
{
  "servers": [
    {
      "serverName": "Production",
      "path": "/var/log/myapp",
      "description": "Production server logs"
    },
    {
      "serverName": "Development",
      "path": "/home/user/dev/logs",
      "description": "Dev server logs"
    }
  ],
  "filePatterns": ["*.log", "*.txt", "*.out"],
  "pollingIntervalSeconds": 2,
  "maxIssuesDisplayed": 500,
  "webServerPort": 8080,
  "storageType": "h2",
  "databasePath": "data/log-dashboard"
}
```

4. Run the dashboard:

```bash
java -jar build/libs/log-issue-dashboard-1.0.0-all.jar ./config
```

5. Open your browser to `http://localhost:8080`

### Command Line Options

```
Usage:
  java -jar log-issue-dashboard.jar <config-folder> [port]

Arguments:
  <config-folder>     Path to folder containing dashboard-config.json
  [port]              HTTP port (overrides config file; default: 8080)

Port Priority:
  1. Command line argument (if provided)
  2. webServerPort from config file (if set)
  3. Default: 8080

Options:
  --help, -h          Show help message
  --version, -v       Show version information
  --create-config <path>  Create a sample configuration file
```

### Custom Port

```bash
# Run on port 9090
java -jar log-issue-dashboard.jar ./config 9090
```

## Configuration

### Full Configuration Options

```json
{
  "servers": [
    {
      "serverName": "ServerName",
      "path": "/path/to/logs",
      "description": "Optional description"
    },
    {
      "serverName": "MainframeServer",
      "path": "/mainframe/logs",
      "description": "IBM Mainframe logs",
      "encoding": "EBCDIC"
    }
  ],
  "watchPaths": ["/legacy/path/support"],
  "filePatterns": ["*.log", "*.txt", "*.out"],
  "exceptionPatterns": [
    ".*Exception.*",
    ".*Error:.*",
    ".*at\\s+[\\w.$]+\\([^)]+\\).*",
    ".*Caused by:.*",
    ".*FATAL.*"
  ],
  "errorPatterns": [
    ".*\\bERROR\\b.*",
    ".*\\bFAILED\\b.*"
  ],
  "warningPatterns": [
    ".*\\bWARN\\b.*",
    ".*\\bWARNING\\b.*"
  ],
  "pollingIntervalSeconds": 2,
  "maxIssuesDisplayed": 500,
  "webServerPort": 8080
}
```

### Configuration Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `servers` | array | `[]` | List of server configurations with name, path, description, and encoding |
| `watchPaths` | array | `[]` | Legacy: simple paths without server names |
| `filePatterns` | array | `["*.log", "*.txt", "*.out"]` | Glob patterns for log files to watch |
| `exceptionPatterns` | array | (see above) | Regex patterns to detect exceptions |
| `errorPatterns` | array | (see above) | Regex patterns to detect errors |
| `warningPatterns` | array | (see above) | Regex patterns to detect warnings |
| `pollingIntervalSeconds` | int | `2` | How often to check for file changes |
| `maxIssuesDisplayed` | int | `500` | Maximum issues to keep in store |
| `webServerPort` | int | `8080` | HTTP server port |
| `storageType` | string | `"h2"` | Storage backend: `"h2"` (persistent) or `"memory"` |
| `databasePath` | string | `"data/log-dashboard"` | Path to H2 database file (without extension) |

### Storage Configuration

The dashboard supports two storage backends:

| Storage Type | Description | Persistence | Capacity |
|-------------|-------------|-------------|----------|
| `h2` (default) | H2 file-based database | ✅ Survives restarts | Up to 100,000 issues |
| `memory` | In-memory storage | ❌ Lost on restart | Up to 50,000 issues |

**H2 Storage Benefits:**
- Issues are persisted to disk and survive application restarts
- Indexed queries for fast filtering by severity, server, and date range
- SQL-powered aggregations for statistics
- Automatic schema migrations for future updates

**Example - Using H2 Storage (default):**
```json
{
  "storageType": "h2",
  "databasePath": "data/log-dashboard",
  "maxIssuesDisplayed": 10000
}
```

**Example - Using In-Memory Storage:**
```json
{
  "storageType": "memory",
  "maxIssuesDisplayed": 500
}
```

**Database Location:**
- Default: `./data/log-dashboard.mv.db`
- The database file will be created automatically on first startup
- Database path is relative to the working directory (or can be absolute)

### Server Configuration Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `serverName` | string | required | Display name for the server |
| `path` | string | required | Path to log directory or file |
| `description` | string | `null` | Optional description |
| `encoding` | string | `UTF-8` | Character encoding for log files |
| `useIconv` | boolean | `false` | Use external `iconv` command for encoding conversion |

### EBCDIC and Character Encoding Support

The dashboard supports various character encodings for log files, including EBCDIC used on IBM mainframes.

#### Supported Encoding Aliases

| Alias | Java Charset | Description |
|-------|--------------|-------------|
| `EBCDIC` | Cp037 | US/Canada EBCDIC |
| `EBCDIC-US` | Cp037 | US/Canada EBCDIC |
| `EBCDIC-INTL` | Cp500 | International EBCDIC |
| `EBCDIC-UNIX` | Cp1047 | Unix/z/OS Open Systems |
| `EBCDIC-1047` | Cp1047 | Unix/z/OS Open Systems |
| `EBCDIC-LATIN1` | Cp1148 | Latin-1 with Euro sign |
| `Cp037` | Cp037 | IBM EBCDIC US/Canada |
| `Cp500` | Cp500 | IBM EBCDIC International |
| `Cp1047` | Cp1047 | IBM EBCDIC z/OS Unix |

#### EBCDIC Configuration Examples

```json
{
  "servers": [
    {
      "serverName": "z/OS-Production",
      "path": "/zos/prod/logs",
      "description": "z/OS Production logs",
      "encoding": "EBCDIC-UNIX"
    },
    {
      "serverName": "AS400-Server",
      "path": "/as400/logs",
      "description": "IBM AS/400 logs",
      "encoding": "Cp037"
    },
    {
      "serverName": "Linux-Server",
      "path": "/var/log/app",
      "description": "Standard Linux logs (UTF-8 default)"
    }
  ]
}
```

#### Using External `iconv` for IBM Encoding Conversion

For better compatibility with IBM mainframe log files, you can use the system's `iconv` command for encoding conversion instead of Java's built-in charset handling. This can provide more accurate conversion for certain EBCDIC code pages.

**When to use `iconv`:**
- When Java's charset handling produces garbled output
- When dealing with complex IBM mainframe encodings
- When you need to match the exact behavior of `iconv -f IBM-1047 -t UTF-8`

**Prerequisites:**
- The `iconv` command must be available on the system
- The dashboard will automatically fall back to Java charset handling if `iconv` is not available

**Configuration with iconv:**

```json
{
  "servers": [
    {
      "serverName": "MAINFRAME-ZOS",
      "path": "/zos/logs",
      "description": "z/OS Unix logs using iconv for IBM-1047 conversion",
      "encoding": "IBM-1047",
      "useIconv": true
    },
    {
      "serverName": "MAINFRAME-US",
      "path": "/mainframe/us/logs",
      "description": "US Mainframe logs using iconv",
      "encoding": "IBM-037",
      "useIconv": true
    }
  ]
}
```

**iconv Encoding Names:**

When using `useIconv: true`, use iconv-compatible encoding names:

| iconv Name | Java Charset | Description |
|------------|--------------|-------------|
| `IBM-1047` | Cp1047 | z/OS Unix (most common for z/OS) |
| `IBM-037` | Cp037 | US/Canada EBCDIC |
| `IBM-500` | Cp500 | International EBCDIC |
| `IBM-1148` | Cp1148 | Latin-1 with Euro sign |
| `ISO8859-1` | ISO-8859-1 | Latin-1 |

**Testing iconv conversion:**

You can test iconv conversion on the command line:

```bash
# Convert from IBM-1047 (EBCDIC) to UTF-8
iconv -f IBM-1047 -t UTF-8 < ebcdic_file.log > utf8_output.log

# Convert from ISO8859-1 to IBM-1047 (for writing EBCDIC)
iconv -f ISO8859-1 -t IBM-1047 < latin1_file.txt > ebcdic_output.txt

# List available encodings on your system
iconv -l | grep IBM
```

## API Examples

### Get Recent Issues

```bash
curl http://localhost:8080/api/issues?limit=10
```

### Get Dashboard Stats

```bash
curl http://localhost:8080/api/stats/dashboard
```

### Get Analysis Report

```bash
curl http://localhost:8080/api/analysis
```

### Filter by Severity

```bash
curl "http://localhost:8080/api/issues?severity=EXCEPTION"
```

### Filter by Server

```bash
curl "http://localhost:8080/api/issues?server=Production"
```

### Filter by Date Range

```bash
# Issues from the last 7 days
curl "http://localhost:8080/api/issues?from=2024-01-01&to=2024-01-07"

# Issues from today only
curl "http://localhost:8080/api/issues?from=2024-01-15&to=2024-01-15"
```

### Combined Filters

```bash
# Critical errors on Production server in the last week
curl "http://localhost:8080/api/issues?server=Production&severity=CRITICAL&from=2024-01-08&to=2024-01-15"
```

### Export Issues

```bash
# Export as JSON
curl "http://localhost:8080/api/export?format=json" > issues.json

# Export as CSV
curl "http://localhost:8080/api/export?format=csv" > issues.csv

# Export filtered issues
curl "http://localhost:8080/api/export?format=csv&server=Production&severity=ERROR" > production-errors.csv
```

### Acknowledge an Issue

```bash
curl -X POST http://localhost:8080/api/issues/{id}/acknowledge
```

### Clear All Issues

```bash
curl -X POST http://localhost:8080/api/issues/clear
```

## Real-time Events (SSE)

Connect to the event stream for real-time updates:

```javascript
const eventSource = new EventSource('http://localhost:8080/events');

eventSource.addEventListener('issue', (event) => {
    const issue = JSON.parse(event.data);
    console.log('New issue:', issue);
});

eventSource.addEventListener('stats', (event) => {
    const stats = JSON.parse(event.data);
    console.log('Stats update:', stats);
});
```

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        Sentinel                              │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  │
│  │  Log File    │  │   Config     │  │  Embedded        │  │
│  │  Watcher     │  │   Watcher    │  │  Tomcat Server   │  │
│  └──────┬───────┘  └──────┬───────┘  └────────┬─────────┘  │
│         │                 │                    │            │
│         ▼                 ▼                    ▼            │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                    Issue Store                       │   │
│  │  (Thread-safe storage with event listeners)          │   │
│  └─────────────────────────────────────────────────────┘   │
│         │                                      │            │
│         ▼                                      ▼            │
│  ┌──────────────┐                    ┌─────────────────┐   │
│  │  Analysis    │                    │  Web Servlets   │   │
│  │  Service     │                    │  (REST + SSE)   │   │
│  └──────────────┘                    └─────────────────┘   │
│                                                │            │
│                                                ▼            │
│                                      ┌─────────────────┐   │
│                                      │   Web Browser   │   │
│                                      │   (Dashboard)   │   │
│                                      └─────────────────┘   │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

## Building from Source

```bash
# Clone the repository
git clone https://github.com/your-repo/log-issue-dashboard.git
cd log-issue-dashboard

# Build the fat JAR
./gradlew fatJar

# The JAR will be at build/libs/log-issue-dashboard-1.0.0-all.jar
```

## IDE Setup

### IntelliJ IDEA

To import this project into IntelliJ IDEA:

1. **Open the project:**
   - Go to `File` → `Open`
   - Navigate to the `log-issue-dashboard` folder (the one containing `build.gradle.kts`)
   - Click `OK`

2. **Import as Gradle project:**
   - When prompted, select "Open as Project"
   - IntelliJ will detect the Gradle build file and import the project automatically

3. **If import fails or dependencies are not resolved:**
   - Open the Gradle tool window (`View` → `Tool Windows` → `Gradle`)
   - Click the refresh icon (🔄) to reload the Gradle project
   - Or run: `./gradlew idea` from the terminal to generate IntelliJ project files

4. **Configure JDK:**
   - Go to `File` → `Project Structure` → `Project`
   - Set the Project SDK to Java 11 or higher
   - Set the Project language level to 11

5. **Verify setup:**
   - Dependencies should be downloaded automatically
   - The `src/main/java` folder should be marked as a source root (blue folder icon)

## Development

### Project Structure

```
src/main/java/com/logdashboard/
├── LogDashboardApp.java       # Main entry point
├── analysis/
│   └── AnalysisService.java   # Statistics and anomaly detection
├── config/
│   ├── ConfigLoader.java      # JSON configuration loading
│   ├── DashboardConfig.java   # Configuration model
│   └── ServerPath.java        # Server path model
├── model/
│   └── LogIssue.java          # Issue data model
├── parser/
│   └── LogParser.java         # Log line parsing
├── store/
│   ├── IssueRepository.java   # Storage interface
│   ├── IssueStore.java        # In-memory storage implementation
│   ├── H2IssueStore.java      # H2 database storage implementation
│   └── DatabaseManager.java   # H2 connection and schema management
├── watcher/
│   ├── ConfigFileWatcher.java # Hot config reload
│   └── LogFileWatcher.java    # File change detection
└── web/
    ├── ApiServlet.java        # REST API endpoints
    ├── EventStreamServlet.java # SSE for real-time updates
    ├── StaticFileServlet.java # Static file serving
    └── WebServer.java         # Embedded Tomcat setup

src/main/resources/static/
├── index.html                 # Dashboard HTML
├── css/
│   └── style.css             # Dashboard styles
└── js/
    └── app.js                # Dashboard JavaScript
```

## License

MIT License - see LICENSE file for details.

## Contributing

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request
