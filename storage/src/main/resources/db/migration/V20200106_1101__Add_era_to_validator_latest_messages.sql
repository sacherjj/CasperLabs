CREATE TABLE latest_messages
(
    key_block_hash BLOB NOT NULL,
    validator  BLOB     NOT NULL,
    block_hash BLOB     NOT NULL,
    PRIMARY KEY (key_block_hash, validator, block_hash),
    FOREIGN KEY (block_hash) REFERENCES block_metadata (block_hash)
) WITHOUT ROWID;

INSERT INTO latest_messages (key_block_hash, validator, block_hash)
SELECT x'', validator, block_hash FROM validator_latest_messages;

DROP TABLE validator_latest_messages;

ALTER TABLE latest_messages RENAME TO validator_latest_messages;
