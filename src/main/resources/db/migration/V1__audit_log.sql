CREATE TABLE audit_log (
                           id         INTEGER PRIMARY KEY AUTOINCREMENT,
                           at         TEXT    NOT NULL,
                           email_id   TEXT    NOT NULL,
                           category   TEXT    NOT NULL,
                           confidence REAL    NOT NULL,
                           reason     TEXT    NOT NULL,
                           action     TEXT    NOT NULL,
                           dry_run    INTEGER NOT NULL
);
CREATE INDEX idx_audit_log_at ON audit_log(at);