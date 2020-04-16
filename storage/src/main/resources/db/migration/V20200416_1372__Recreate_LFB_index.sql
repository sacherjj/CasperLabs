DROP INDEX lfb_main_chain_idx;

CREATE INDEX lfb_main_chain_idx ON block_metadata (j_rank, block_hash) WHERE is_main_chain and is_finalized;
