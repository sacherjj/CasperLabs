ALTER TABLE block_metadata RENAME COLUMN validator_block_seq_num TO create_time_millis;
DROP INDEX idx_validator_block_seq_num;
CREATE INDEX idx_create_time_millis ON block_metadata (validator, create_time_millis);