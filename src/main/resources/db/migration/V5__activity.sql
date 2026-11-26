-- Every email actually acted on, manual or autopilot. Feeds the activity feed and stats bar.
CREATE TABLE activity (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    at         TEXT    NOT NULL,            -- ISO-8601 instant
    account_id TEXT    NOT NULL,
    email_id   TEXT    NOT NULL,
    sender     TEXT,
    subject    TEXT,
    category   TEXT    NOT NULL,
    confidence REAL    NOT NULL,
    reason     TEXT,
    action     TEXT    NOT NULL,
    source     TEXT    NOT NULL CHECK (source IN ('RULE', 'MODEL', 'FALLBACK')),
    overridden INTEGER NOT NULL
);
CREATE INDEX idx_activity_at ON activity(at DESC);
