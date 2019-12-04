CREATE TABLE latest_messages
(
    validator  BLOB    NOT NULL,
    block_hash BLOB    NOT NULL,
    PRIMARY KEY (validator, block_hash),
    FOREIGN KEY (block_hash) REFERENCES block_metadata (block_hash)
) WITHOUT ROWID;

INSERT INTO latest_messages (validator, block_hash)
SELECT validator, block_hash FROM validator_latest_messages;

DROP TABLE validator_latest_messages;

ALTER TABLE latest_messages RENAME TO validator_latest_messages;