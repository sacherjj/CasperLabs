ALTER TABLE block_metadata RENAME COLUMN rank TO j_rank;

ALTER TABLE block_metadata ADD COLUMN p_rank INTEGER NOT NULL DEFAULT 0;
