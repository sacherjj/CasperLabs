CREATE TABLE lfb_chain_tmp (
    block_hash blob PRIMARY KEY NOT NULL,
    indirectly_finalized blob
);

INSERT INTO lfb_chain_tmp 
SELECT block_hash, indirectly_finalized FROM lfb_chain ORDER BY id asc;

DROP TABLE lfb_chain;

ALTER TABLE lfb_chain_tmp RENAME TO lfb_chain;
