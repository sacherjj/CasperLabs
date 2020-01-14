CREATE INDEX lfb_main_chain_idx ON block_metadata (rank, block_hash) where is_main_chain=true and is_finalized=true;
