CREATE TABLE IF NOT EXISTS prefilter_rules (
                                               id          TEXT PRIMARY KEY,
                                               match_field TEXT NOT NULL,
                                               pattern     TEXT NOT NULL,
                                               action      TEXT NOT NULL,
                                               category    TEXT NOT NULL,
                                               created_at  TEXT NOT NULL
);