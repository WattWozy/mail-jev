-- Folders the user wants mail sorted into; jev chooses between them by description.
CREATE TABLE user_folders (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL UNIQUE COLLATE NOCASE,
    description TEXT NOT NULL,
    created_at  TEXT NOT NULL
);
