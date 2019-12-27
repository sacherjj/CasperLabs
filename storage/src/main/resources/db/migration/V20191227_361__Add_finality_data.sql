ALTER TABLE block_metadata
    ADD COLUMN is_finalized BOOLEAN NOT NULL DEFAULT FALSE;

-- We need to differentiate between blocks from the main chain and secondary parents
-- because we want to be able to restore from LFB of the main chain on startup.
ALTER TABLE block_metadata
    ADD COLUMN is_main_chain BOOLEAN NOT NULL DEFAULT FALSE;

-- quorum is the weight of validators' stake that finalized the block.
-- It might become useful when handling equivocation catastrophe which UN-finalizes a block.
ALTER TABLE block_metadata
    ADD COLUMN quorum INTEGER;
