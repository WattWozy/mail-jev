CREATE TABLE credentials (
                             id          INTEGER PRIMARY KEY AUTOINCREMENT,
                             service     TEXT    NOT NULL,          -- e.g. "imap", "smtp", "gmail"
                             username    TEXT    NOT NULL,
                             secret      TEXT    NOT NULL,          -- password / token / app password
                             created_at  TEXT    NOT NULL,
                             updated_at  TEXT    NOT NULL,
                             UNIQUE(service, username)
);

CREATE INDEX idx_credentials_service ON credentials(service);