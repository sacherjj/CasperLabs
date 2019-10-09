CREATE TABLE block_parents
(
    parent_block_hash BLOB NOT NULL,
    child_block_hash  BLOB NOT NULL,
    PRIMARY KEY (parent_block_hash, child_block_hash),
    FOREIGN KEY (parent_block_hash) REFERENCES block_metadata (block_hash),
    FOREIGN KEY (child_block_hash) REFERENCES block_metadata (block_hash)
) WITHOUT ROWID;

CREATE INDEX idx_block_parents
    ON block_parents (child_block_hash, parent_block_hash);

CREATE TABLE block_justifications
(
    justification_block_hash BLOB NOT NULL,
    block_hash               BLOB NOT NULL,
    PRIMARY KEY (justification_block_hash, block_hash),
    FOREIGN KEY (block_hash) REFERENCES block_metadata (block_hash),
    FOREIGN KEY (justification_block_hash) REFERENCES block_metadata (block_hash)
) WITHOUT ROWID;

CREATE INDEX idx_block_justifications
    ON block_justifications (block_hash, justification_block_hash);

CREATE TABLE validator_latest_messages
(
    validator  BLOB    NOT NULL PRIMARY KEY,
    block_hash BLOB    NOT NULL,
    rank       INTEGER NOT NULL,
    FOREIGN KEY (block_hash) REFERENCES block_metadata (block_hash)
);

CREATE TABLE block_metadata
(
    block_hash BLOB    NOT NULL PRIMARY KEY,
    validator  BLOB    NOT NULL,
    rank       INTEGER NOT NULL,
    --BlockSummary
    data       BLOB    NOT NULL
);

CREATE INDEX idx_block_metadata_rank_block_hash
    ON block_metadata (rank, block_hash);

CREATE INDEX idx_block_metadata_validator_block_hash
    ON block_metadata (validator, block_hash);
