#!/bin/bash
# Quick script to add sample errors to log file immediately
# Usage: ./add-errors-now.sh [log_file]

LOG_FILE="${1:-/workspace/log-issue-dashboard/sample-logs/live-test.log}"
timestamp=$(date '+%Y-%m-%d %H:%M:%S.%3N')

echo "Adding sample errors to: $LOG_FILE"

cat >> "$LOG_FILE" << EOF
$timestamp INFO  [main] Processing request batch...
$timestamp WARN  [http-thread-1] Slow query detected: 2500ms
$timestamp ERROR [http-thread-2] Authentication FAILED for user: testuser
$timestamp ERROR [worker-1] Database connection timeout
java.sql.SQLException: Connection timed out after 30 seconds
	at com.mysql.jdbc.ConnectionImpl.connect(ConnectionImpl.java:456)
	at com.myapp.db.ConnectionPool.getConnection(ConnectionPool.java:89)
	at com.myapp.repository.UserRepository.findById(UserRepository.java:34)
$timestamp WARNING [scheduler] Background job delayed
$timestamp FATAL [main] Critical error - service shutting down
java.lang.OutOfMemoryError: Metaspace
	at java.lang.ClassLoader.defineClass(ClassLoader.java:756)
	at com.myapp.plugins.PluginLoader.load(PluginLoader.java:123)
Caused by: java.lang.RuntimeException: Too many classes loaded
	at com.myapp.plugins.PluginManager.register(PluginManager.java:67)
	... 8 more
$timestamp ERROR [api-service] External API call FAILURE: 503 Service Unavailable
$timestamp WARN  [monitor] Memory usage critical: 95%
$timestamp ERROR [payment-gateway] Transaction failed
com.stripe.exception.CardException: Your card was declined
	at com.stripe.Stripe.charge(Stripe.java:234)
	at com.myapp.payment.PaymentService.processPayment(PaymentService.java:89)
EOF

echo "Done! Added 10 log entries with various error types."
echo ""
echo "Summary of added errors:"
echo "  - 1 INFO"
echo "  - 3 WARN/WARNING"
echo "  - 3 ERROR (simple)"
echo "  - 3 EXCEPTION (with stack traces)"
