-- Activity used to record the account as "username@host"; everything else uses "service|username".
-- Map old rows to the saved account they belong to; rows with no matching saved account stay as they are.

UPDATE activity
SET account_id = (
    SELECT c.service || '|' || c.username
    FROM credentials c
    WHERE activity.account_id = c.username || '@' || CASE c.service
        WHEN 'gmail'   THEN 'imap.gmail.com'
        WHEN 'gmx'     THEN 'imap.gmx.com'
        WHEN 'outlook' THEN 'outlook.office365.com'
        WHEN 'yahoo'   THEN 'imap.mail.yahoo.com'
        WHEN 'icloud'  THEN 'imap.mail.me.com'
        ELSE substr(c.service, length('imap:') + 1)
    END
)
WHERE account_id NOT LIKE '%|%'
  AND EXISTS (
    SELECT 1
    FROM credentials c
    WHERE activity.account_id = c.username || '@' || CASE c.service
        WHEN 'gmail'   THEN 'imap.gmail.com'
        WHEN 'gmx'     THEN 'imap.gmx.com'
        WHEN 'outlook' THEN 'outlook.office365.com'
        WHEN 'yahoo'   THEN 'imap.mail.yahoo.com'
        WHEN 'icloud'  THEN 'imap.mail.me.com'
        ELSE substr(c.service, length('imap:') + 1)
    END
);
