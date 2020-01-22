-- Stores a chain of finalized blocks (according to the node's internal finalizer parameters).
-- In the face of an equivocation catastrophe that un-finalizes a block, those will be removed
-- from the table. Because the chain of finalized block is continuous, the removal will always drop
-- blocks from the tail.
CREATE TABLE lfb_chain (
    id integer primary key autoincrement,
    block_hash blob NOT NULL,
    indirectly_finalized blob
);
