package com.logdashboard.store;

import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Periodically deletes issues that have not occurred within the retention period.
 * Without it the issue store grows forever, and statistics queries and searches over
 * it get slower over time.
 */
public class IssueRetentionTask {

    private static final long INITIAL_DELAY_SECONDS = 60;
    private static final long INTERVAL_HOURS = 6;

    private final IssueRepository store;
    private final int retentionDays;
    private final Runnable onIssuesDeleted;
    private final Consumer<String> statusCallback;
    private final ScheduledExecutorService scheduler;

    /**
     * @param onIssuesDeleted called after a cleanup removed issues (e.g. to refresh cached statistics)
     */
    public IssueRetentionTask(IssueRepository store, int retentionDays, Runnable onIssuesDeleted,
                              Consumer<String> statusCallback) {
        this.store = store;
        this.retentionDays = retentionDays;
        this.onIssuesDeleted = onIssuesDeleted;
        this.statusCallback = statusCallback;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "IssueRetention");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::runOnce, INITIAL_DELAY_SECONDS,
            TimeUnit.HOURS.toSeconds(INTERVAL_HOURS), TimeUnit.SECONDS);
        updateStatus("Issue retention enabled: issues not seen for " + retentionDays
            + " day(s) are deleted every " + INTERVAL_HOURS + " hours");
    }

    /**
     * Deletes expired issues now.
     *
     * @return number of issues deleted
     */
    public int runOnce() {
        try {
            int deleted = store.deleteIssuesNotSeenSince(LocalDateTime.now().minusDays(retentionDays));
            if (deleted > 0) {
                updateStatus("Deleted " + deleted + " issue(s) not seen in the last " + retentionDays + " day(s)");
                if (onIssuesDeleted != null) {
                    onIssuesDeleted.run();
                }
            }
            return deleted;
        } catch (RuntimeException e) {
            // Never let an exception cancel future scheduled runs
            updateStatus("Retention cleanup failed: " + e);
            return 0;
        }
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    private void updateStatus(String status) {
        if (statusCallback != null) {
            statusCallback.accept(status);
        }
    }
}
