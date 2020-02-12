-- The table is defined without rowid so there's no easy way
-- derive `is_main` for existing records.
ALTER TABLE block_parents
    ADD COLUMN is_main BOOLEAN NOT NULL DEFAULT FALSE;
