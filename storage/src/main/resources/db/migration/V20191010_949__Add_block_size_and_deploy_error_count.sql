ALTER table block_metadata
    ADD COLUMN block_size INTEGER;
ALTER TABLE block_metadata
    ADD COLUMN deploy_error_count INTEGER;