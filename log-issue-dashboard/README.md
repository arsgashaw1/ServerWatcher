# Log Issue Dashboard

A modern web-based dashboard for real-time log file monitoring with analysis features. Built with embedded Tomcat and a beautiful, responsive UI.

![Dashboard Preview](docs/dashboard-preview.png)

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

### Analysis & Insights
- **Trend Analysis**: Track issue rate over time (minute-by-minute and hourly views)
- **Severity Distribution**: See breakdown of Critical, Exception, Error, and Warning issues
- **Server Health Scores**: Quick overview of which servers have the most issues
- **Recurring Issue Detection**: Automatically identifies repeated problems
- **Anomaly Detection**: Alerts for spikes, new exception types, and server concentration
- **Root Cause Candidates**: Identifies potential hotspots in your code

### REST API
Full API access for integration with other tools:
- `GET /api/issues` - List all issues with pagination and filtering
- `GET /api/issues/recent` - Get issues from the last N minutes
- `GET /api/stats/dashboard` - Comprehensive dashboard statistics
- `GET /api/analysis` - Detailed analysis report
- `GET /api/analysis/anomalies` - Detected anomalies
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
  "webServerPort": 8080
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
  [port]              HTTP port (default: 8080)

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
| `servers` | array | `[]` | List of server configurations with name, path, and description |
| `watchPaths` | array | `[]` | Legacy: simple paths without server names |
| `filePatterns` | array | `["*.log", "*.txt", "*.out"]` | Glob patterns for log files to watch |
| `exceptionPatterns` | array | (see above) | Regex patterns to detect exceptions |
| `errorPatterns` | array | (see above) | Regex patterns to detect errors |
| `warningPatterns` | array | (see above) | Regex patterns to detect warnings |
| `pollingIntervalSeconds` | int | `2` | How often to check for file changes |
| `maxIssuesDisplayed` | int | `500` | Maximum issues to keep in memory |
| `webServerPort` | int | `8080` | HTTP server port |

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
│                    Log Issue Dashboard                       │
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
│   └── IssueStore.java        # Thread-safe issue storage
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
