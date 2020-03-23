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

-- Add ticks to the eras to be able to tell when a message is after the end
-- without having to retrieve the full era record.
ALTER TABLE eras ADD start_tick INTEGER NOT NULL DEFAULT 0;
ALTER TABLE eras ADD end_tick INTEGER NOT NULL DEFAULT 0;

UPDATE eras SET start_tick = start_millis, end_tick = end_millis;
