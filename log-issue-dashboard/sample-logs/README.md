# Sample Log Files for Testing

This directory contains sample log files and scripts to test the Log Issue Dashboard.

## Important: How Log Detection Works

The dashboard watches for **NEW** log entries only. It starts tracking from the **end** of existing log files. This means:

- Existing log content when the dashboard starts is **NOT** detected
- Only new lines added **AFTER** the dashboard starts will trigger alerts

## Quick Test Steps

### Step 1: Start the Dashboard
```bash
cd /workspace/log-issue-dashboard
./gradlew run
```

### Step 2: Add Sample Errors (in a new terminal)
```bash
# Quick one-shot - adds 10 sample errors immediately
./sample-logs/add-errors-now.sh

# OR continuous generation - adds new error every 3 seconds
./sample-logs/generate-errors.sh
```

### Step 3: View in Dashboard
Open browser to `http://localhost:8080`

## Test Scripts

| Script | Description |
|--------|-------------|
| `add-errors-now.sh` | Adds 10 sample errors immediately to `live-test.log` |
| `generate-errors.sh` | Continuously generates errors every 3 seconds |

## Sample Log Files

These files contain pre-generated logs for reference. To test with them, you would need to:
1. Start the dashboard
2. Append new content to these files

| File | Content |
|------|---------|
| `application-server.log` | General app server errors, NullPointerException, payment errors |
| `database-errors.log` | SQL errors, connection pool issues, deadlocks |
| `web-server.log` | Tomcat errors, SSL issues, OutOfMemoryError |
| `batch-processing.log` | Batch job failures, index corruption |
| `microservices.log` | Circuit breaker, Kafka, Redis errors |
| `live-test.log` | Empty file for testing with scripts |

## Types of Errors Included

- **EXCEPTION** - Full Java stack traces (NullPointerException, OutOfMemoryError, SQLException, etc.)
- **ERROR** - Error log messages with ERROR, FAILED, FAILURE keywords
- **WARNING** - Warning messages with WARN/WARNING keywords
- **FATAL** - Critical errors

## Manual Testing

You can also manually append errors:

```bash
# Simple error
echo "$(date '+%Y-%m-%d %H:%M:%S') ERROR [test] Something went wrong" >> sample-logs/live-test.log

# Exception with stack trace
cat >> sample-logs/live-test.log << 'EOF'
2025-12-16 10:00:00 ERROR [test] Exception occurred
java.lang.NullPointerException: null value
	at com.test.MyClass.method(MyClass.java:123)
	at com.test.Main.run(Main.java:45)
EOF
```
