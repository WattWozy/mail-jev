-- Singleton row (id = 1) holding the autopilot configuration.
CREATE TABLE autopilot_settings (
    id               INTEGER PRIMARY KEY CHECK (id = 1),
    enabled          INTEGER NOT NULL,
    interval_seconds INTEGER NOT NULL,
    batch_limit      INTEGER NOT NULL,
    account_id       TEXT,              -- "service|username" autopilot reconnects to; NULL = none
    updated_at       TEXT    NOT NULL
);
