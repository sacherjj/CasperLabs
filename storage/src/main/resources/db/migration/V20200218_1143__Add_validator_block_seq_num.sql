ALTER TABLE block_metadata ADD COLUMN validator_block_seq_num INT NOT NULL DEFAULT 0;
CREATE INDEX idx_validator_block_seq_num ON block_metadata (validator, validator_block_seq_num);