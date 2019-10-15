ALTER table block_metadata
    ADD COLUMN block_size INTEGER NOT NULL DEFAULT 0;

ALTER TABLE block_metadata
    ADD COLUMN deploy_error_count INTEGER NOT NULL DEFAULT 0;
