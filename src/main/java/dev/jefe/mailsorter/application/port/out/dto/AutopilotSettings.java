package dev.jefe.mailsorter.application.port.out.dto;

/** Persisted autopilot configuration. {@code accountId} is the saved account autopilot reconnects to. */
public record AutopilotSettings(boolean enabled, int intervalSeconds, int limit, String accountId) {

    public static final int MIN_INTERVAL_SECONDS = 10;
    public static final int MAX_INTERVAL_SECONDS = 86_400;
    public static final int MIN_LIMIT = 1;
    public static final int MAX_LIMIT = 200;

    public static AutopilotSettings defaults() {
        return new AutopilotSettings(false, 120, 10, null);
    }

    public AutopilotSettings {
        if (intervalSeconds < MIN_INTERVAL_SECONDS || intervalSeconds > MAX_INTERVAL_SECONDS) {
            throw new IllegalArgumentException("intervalSeconds must be in ["
                    + MIN_INTERVAL_SECONDS + "," + MAX_INTERVAL_SECONDS + "]");
        }
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be in [" + MIN_LIMIT + "," + MAX_LIMIT + "]");
        }
    }
}
