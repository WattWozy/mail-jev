-- User folders: a rule can file matching mail into a folder the user named; NULL = no folder.
ALTER TABLE prefilter_rules ADD COLUMN folder TEXT;
ALTER TABLE activity        ADD COLUMN folder TEXT;
ALTER TABLE audit_log       ADD COLUMN folder TEXT;
