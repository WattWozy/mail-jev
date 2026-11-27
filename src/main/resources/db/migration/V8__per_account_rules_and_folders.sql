-- Rules and folders belong to one account ("service|username", same key as saved credentials).
-- Rows made before this migration are copied to every saved account; with no saved account they are dropped,
-- since they could never apply to anything.

CREATE TABLE prefilter_rules_v8 (
    account_id  TEXT NOT NULL,
    id          TEXT NOT NULL,
    match_field TEXT NOT NULL,
    pattern     TEXT NOT NULL,
    action      TEXT NOT NULL,
    category    TEXT NOT NULL,
    folder      TEXT,
    created_at  TEXT NOT NULL,
    PRIMARY KEY (account_id, id)
);

INSERT INTO prefilter_rules_v8(account_id, id, match_field, pattern, action, category, folder, created_at)
SELECT c.service || '|' || c.username, r.id, r.match_field, r.pattern, r.action, r.category, r.folder, r.created_at
FROM prefilter_rules r CROSS JOIN credentials c;

DROP TABLE prefilter_rules;
ALTER TABLE prefilter_rules_v8 RENAME TO prefilter_rules;

CREATE TABLE user_folders_v8 (
    account_id  TEXT NOT NULL,
    id          TEXT NOT NULL,
    name        TEXT NOT NULL COLLATE NOCASE,
    description TEXT NOT NULL,
    created_at  TEXT NOT NULL,
    PRIMARY KEY (account_id, id),
    UNIQUE (account_id, name)
);

INSERT INTO user_folders_v8(account_id, id, name, description, created_at)
SELECT c.service || '|' || c.username, f.id, f.name, f.description, f.created_at
FROM user_folders f CROSS JOIN credentials c;

DROP TABLE user_folders;
ALTER TABLE user_folders_v8 RENAME TO user_folders;
