-- Stores a list of block's ancestors at power of 2 heights (1, 2, 4, 8, 16, …)
CREATE TABLE message_ancestors_skiplist (
    block_hash BLOB NOT NULL,
    distance INTEGER NOT NULL,
    ancestor_hash BLOB NOT NULL,
    PRIMARY KEY (block_hash, distance)
) WITHOUT ROWID;
