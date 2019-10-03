CREATE TABLE transforms
(
    block_hash BLOB NOT NULL,
    --TransformEntry
    data  BLOB NOT NULL,
    FOREIGN KEY (block_hash) REFERENCES block_metadata (block_hash)
);

CREATE INDEX idx_transforms_block_hash ON transforms (block_hash);


