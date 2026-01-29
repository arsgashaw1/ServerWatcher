# Sentinel - User Guide & Architecture Overview

---

## 📧 Email Template for Introducing Sentinel

**Subject: Introducing Sentinel - Real-time Log Monitoring Dashboard**

---

Hi Team,

I'm excited to introduce **Sentinel**, our new log monitoring and anomaly detection platform. This tool will help us proactively identify and respond to issues across our servers.

### What is Sentinel?
Sentinel is a web-based dashboard that monitors log files in real-time and automatically detects exceptions, errors, and warnings. It provides:

- **Real-time alerts** when issues appear in log files
- **Multi-server support** to monitor all environments from one place
- **Visual analytics** showing trends and severity distribution
- **Anomaly detection** to catch unusual patterns

### How to Access
- **Dashboard URL**: `http://<server-ip>:8080`
- **Configuration**: Contact the infrastructure team to add your log paths

### Quick Start
1. Open the dashboard in your browser
2. Select your server(s) from the filter dropdown
3. Watch for new issues in real-time
4. Click any issue to see full details and stack trace

### Documentation
See the attached User Guide for complete instructions, or visit our internal wiki.

Please reach out if you have questions or need your servers added to the monitoring.

Best regards,
[Your Name]

---

## 🏗️ Architecture Overview

> **Note to Users**: Please update this section as the architecture evolves!

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           SENTINEL PLATFORM                              │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│   ┌────────────────┐    ┌────────────────┐    ┌──────────────────────┐  │
│   │  LOG FILE      │    │    CONFIG      │    │    EMBEDDED          │  │
│   │  WATCHER       │    │    WATCHER     │    │    TOMCAT SERVER     │  │
│   │                │    │                │    │                      │  │
│   │  - Monitors    │    │  - Hot reload  │    │  - REST API          │  │
│   │    log files   │    │  - Auto-apply  │    │  - SSE streaming     │  │
│   │  - Pattern     │    │    changes     │    │  - Static files      │  │
│   │    matching    │    │                │    │                      │  │
│   └───────┬────────┘    └───────┬────────┘    └──────────┬───────────┘  │
│           │                     │                        │               │
│           ▼                     ▼                        ▼               │
│   ┌─────────────────────────────────────────────────────────────────┐   │
│   │                        ISSUE STORE                               │   │
│   │                                                                  │   │
│   │    Thread-safe in-memory storage with event listeners            │   │
│   │    - Stores detected issues                                      │   │
│   │    - Notifies subscribers of new issues                          │   │
│   │    - Supports filtering and pagination                           │   │
│   └─────────────────────────────────────────────────────────────────┘   │
│           │                                             │                │
│           ▼                                             ▼                │
│   ┌────────────────────┐                  ┌──────────────────────────┐  │
│   │  ANALYSIS SERVICE  │                  │      WEB INTERFACE       │  │
│   │                    │                  │                          │  │
│   │  - Trend analysis  │                  │  - Dashboard UI          │  │
│   │  - Anomaly detect  │                  │  - Real-time charts      │  │
│   │  - Root cause ID   │                  │  - Issue details modal   │  │
│   └────────────────────┘                  └──────────────────────────┘  │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### Component Details

| Component | File | Purpose |
|-----------|------|---------|
| **Main Application** | `LogDashboardApp.java` | Entry point, initializes all components |
| **Log File Watcher** | `LogFileWatcher.java` | Monitors configured directories for log changes |
| **Config Watcher** | `ConfigFileWatcher.java` | Enables hot-reload of configuration |
| **Log Parser** | `LogParser.java` | Parses log lines and detects issues |
| **Issue Store** | `IssueStore.java` | Thread-safe storage for detected issues |
| **Analysis Service** | `AnalysisService.java` | Generates statistics and detects anomalies |
| **Web Server** | `WebServer.java` | Embedded Tomcat server setup |
| **API Servlet** | `ApiServlet.java` | REST API endpoints |
| **Event Stream** | `EventStreamServlet.java` | Server-Sent Events for real-time updates |

### Data Flow

```
Log Files → Watcher → Parser → Issue Store → Analysis Service
                                    ↓
                              Web Server → Browser (Dashboard)
                                    ↓
                              SSE Events → Real-time Updates
```

---

## 📖 User Guide

### Getting Started

#### Prerequisites
- Java 11 or higher installed
- Access to log directories you want to monitor
- Network access to the dashboard port (default: 8080)

#### Installation Steps

1. **Build the application** (if not pre-built):
   ```bash
   cd log-issue-dashboard
   ./gradlew build
   ```

2. **Create configuration folder**:
   ```bash
   mkdir config
   ```

3. **Create configuration file** (`config/dashboard-config.json`):
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
         "description": "Dev environment logs"
       }
     ],
     "filePatterns": ["*.log", "*.txt", "*.out"],
     "pollingIntervalSeconds": 2,
     "maxIssuesDisplayed": 500,
     "webServerPort": 8080
   }
   ```

4. **Run the dashboard**:
   ```bash
   java -jar build/libs/log-issue-dashboard-1.0.0-all.jar ./config
   ```

5. **Open browser**: Navigate to `http://localhost:8080`

---

### Configuration Guide

#### Adding a New Server

Add a new entry to the `servers` array in your config file:

```json
{
  "serverName": "NEW-SERVER",
  "path": "/path/to/logs",
  "description": "Description of this server"
}
```

#### For EBCDIC/Mainframe Logs

If monitoring IBM mainframe logs, specify the encoding:

```json
{
  "serverName": "MAINFRAME",
  "path": "/zos/logs",
  "description": "z/OS logs",
  "encoding": "IBM-1047"
}
```

#### Custom Issue Patterns

You can customize what Sentinel detects by modifying these patterns:

| Pattern Type | What it Detects | Example |
|--------------|-----------------|---------|
| `exceptionPatterns` | Exceptions, stack traces | `.*Exception.*` |
| `errorPatterns` | Error messages | `.*\\bERROR\\b.*` |
| `warningPatterns` | Warning messages | `.*\\bWARN\\b.*` |

---

### Using the Dashboard

#### Main Dashboard View

| Section | Description |
|---------|-------------|
| **Issue Feed** | Live stream of detected issues (newest first) |
| **Charts** | Visual breakdown by severity and server |
| **Filters** | Filter by server, severity, and date range |
| **Stats** | Total counts and trend indicators |

#### Filtering Issues

1. **By Server**: Select from the server dropdown
2. **By Severity**: Choose Critical, Exception, Error, or Warning
3. **By Date**: Use the date range picker
4. **Combined**: Apply multiple filters together

#### Viewing Issue Details

1. Click any issue in the feed
2. A modal appears with:
   - Full log message
   - Complete stack trace (if available)
   - Timestamp and source file
   - Server information

#### Exporting Data

1. Apply desired filters
2. Click **Export** button
3. Choose format: JSON or CSV

---

### REST API Reference

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/issues` | GET | List issues (supports filters) |
| `/api/issues/recent` | GET | Recent issues (last N minutes) |
| `/api/stats/dashboard` | GET | Dashboard statistics |
| `/api/analysis` | GET | Detailed analysis report |
| `/api/analysis/anomalies` | GET | Detected anomalies |
| `/api/export?format=csv` | GET | Export as CSV |
| `/api/issues/{id}/acknowledge` | POST | Acknowledge an issue |
| `/api/issues/clear` | POST | Clear all issues |

#### Example API Calls

```bash
# Get last 10 issues
curl http://localhost:8080/api/issues?limit=10

# Get production server issues only
curl "http://localhost:8080/api/issues?server=Production"

# Get issues from date range
curl "http://localhost:8080/api/issues?from=2024-01-01&to=2024-01-31"

# Export to CSV
curl "http://localhost:8080/api/export?format=csv" > issues.csv
```

---

### Troubleshooting

| Issue | Solution |
|-------|----------|
| Dashboard won't start | Check Java 11+ is installed: `java -version` |
| No issues appearing | Verify log paths in config are correct and accessible |
| Port already in use | Change `webServerPort` in config or use command line: `java -jar ... ./config 9090` |
| Garbled characters | Check encoding setting matches your log files |
| Config changes not applied | Wait 5 seconds for hot reload, or restart |

---

### Best Practices

1. **Start small**: Begin with one or two servers, then expand
2. **Tune patterns**: Adjust error patterns to match your log format
3. **Set appropriate polling**: 2-5 seconds is usually sufficient
4. **Monitor resource usage**: Keep `maxIssuesDisplayed` reasonable (500-1000)
5. **Use filters**: Filter by date range for better performance with large logs

---

## 📝 Change Log

> **Users**: Please document significant changes to the system below.

| Date | Change | Author |
|------|--------|--------|
| YYYY-MM-DD | Initial deployment | [Name] |
| | | |

---

## 🔄 Update This Document

This document should be kept up-to-date as the system evolves. When making changes:

1. Update the **Architecture Overview** if components change
2. Add new **Configuration Options** as they're added
3. Document **API Changes** in the reference section
4. Record significant changes in the **Change Log**

**Last Updated**: [Insert Date]
**Maintained By**: [Team/Person Name]
