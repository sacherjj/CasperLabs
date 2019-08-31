CREATE TABLE blocks
(
    block_hash     BLOB PRIMARY KEY NOT NULL,
    block_hash_hex TEXT             NOT NULL,
    data           BLOB             NOT NULL
);

CREATE UNIQUE INDEX idx_blocks_hex ON blocks (block_hash_hex);
