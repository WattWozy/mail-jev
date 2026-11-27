-- Rules may only file into defined folders. Every folder a rule already files into becomes a defined folder
-- (one per account and name, ignoring case), described by the patterns of its rules until the user edits it.

INSERT INTO user_folders(account_id, id, name, description, created_at)
SELECT r.account_id,
       lower(hex(randomblob(16))),
       MIN(r.folder),
       substr('Mail like: ' || group_concat(r.pattern, ', '), 1, 300),
       MIN(r.created_at)
FROM prefilter_rules r
WHERE r.folder IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_folders f
                  WHERE f.account_id = r.account_id AND f.name = r.folder)   -- name is COLLATE NOCASE
GROUP BY r.account_id, lower(r.folder);

-- rules use the defined folder's own spelling, so the mailbox gets exactly one folder
UPDATE prefilter_rules
SET folder = (SELECT f.name FROM user_folders f
              WHERE f.account_id = prefilter_rules.account_id AND f.name = prefilter_rules.folder)
WHERE folder IS NOT NULL;
