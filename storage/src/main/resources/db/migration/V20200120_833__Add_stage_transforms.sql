ALTER TABLE transforms ADD COLUMN stage INTEGER NOT NULL DEFAULT 0;

UPDATE transforms SET stage = 0;
