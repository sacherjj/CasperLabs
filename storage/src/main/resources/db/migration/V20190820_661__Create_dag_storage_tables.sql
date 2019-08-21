CREATE TABLE dag_storage_topological_sorting
(
    parent_block_hash BLOB    NOT NULL,
    child_block_hash  BLOB    NOT NULL,
    child_rank        INTEGER NOT NULL,
    PRIMARY KEY (parent_block_hash, child_block_hash, child_rank)
) WITHOUT ROWID;

CREATE INDEX idx_dag_storage_topological_sorting_child_rank_child_block_hash
    ON dag_storage_topological_sorting (child_rank, child_block_hash);

CREATE TABLE dag_storage_justifications
(
    justification_block_hash BLOB NOT NULL,
    block_hash               BLOB NOT NULL,
    PRIMARY KEY (justification_block_hash, block_hash)
) WITHOUT ROWID;

CREATE TABLE dag_storage_blocks_metadata
(
    block_hash BLOB    NOT NULL PRIMARY KEY,
    validator  BLOB    NOT NULL,
    rank       INTEGER NOT NULL,
    data       BLOB    NOT NULL
);

CREATE INDEX idx_dag_storage_blocks_metadata_validator_rank
    ON dag_storage_blocks_metadata (validator, rank);
