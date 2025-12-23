#!/bin/bash
# Script to generate sample log errors for testing the Log Issue Dashboard
# Run this AFTER the dashboard is started to see errors appear in real-time

LOG_FILE="${1:-/workspace/log-issue-dashboard/sample-logs/live-test.log}"

echo "Generating errors to: $LOG_FILE"
echo "Press Ctrl+C to stop"
echo ""

# Clear or create the log file
> "$LOG_FILE"

sleep 2

# Generate errors in a loop
counter=1
while true; do
    timestamp=$(date '+%Y-%m-%d %H:%M:%S.%3N')
    
    case $((counter % 10)) in
        1)
            echo "$timestamp INFO  [main] Application running normally..." >> "$LOG_FILE"
            ;;
        2)
            echo "$timestamp WARN  [http-thread-1] High memory usage detected: 85%" >> "$LOG_FILE"
            ;;
        3)
            echo "$timestamp ERROR [http-thread-2] Database connection failed" >> "$LOG_FILE"
            ;;
        4)
            echo "$timestamp ERROR [worker-3] Request processing FAILED for user ID: $counter" >> "$LOG_FILE"
            ;;
        5)
            cat >> "$LOG_FILE" << EOF
$timestamp ERROR [http-thread-4] Unhandled exception occurred
java.lang.NullPointerException: Cannot invoke method on null object
	at com.myapp.service.UserService.getUser(UserService.java:$counter)
	at com.myapp.controller.UserController.handleRequest(UserController.java:89)
	at org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:897)
EOF
            ;;
        6)
            echo "$timestamp WARNING [scheduler-1] Job execution delayed by 5 seconds" >> "$LOG_FILE"
            ;;
        7)
            cat >> "$LOG_FILE" << EOF
$timestamp FATAL [main] Critical system failure
java.lang.OutOfMemoryError: Java heap space
	at java.util.Arrays.copyOf(Arrays.java:3210)
	at com.myapp.cache.CacheManager.store(CacheManager.java:156)
Caused by: java.lang.RuntimeException: Cache overflow
	at com.myapp.cache.CacheManager.checkCapacity(CacheManager.java:89)
	... 5 more
EOF
            ;;
        8)
            echo "$timestamp ERROR [api-gateway] FAILURE: Upstream service unavailable" >> "$LOG_FILE"
            ;;
        9)
            echo "$timestamp WARN  [monitor] CPU usage at 92% - throttling requests" >> "$LOG_FILE"
            ;;
        0)
            cat >> "$LOG_FILE" << EOF
$timestamp ERROR [payment-service] Payment processing error
com.stripe.exception.APIConnectionException: Connection timeout
	at com.stripe.net.HttpClient.request(HttpClient.java:234)
	at com.myapp.payment.StripeGateway.charge(StripeGateway.java:78)
EOF
            ;;
    esac
    
    echo "[$counter] Generated log entry at $timestamp"
    counter=$((counter + 1))
    sleep 3
done
