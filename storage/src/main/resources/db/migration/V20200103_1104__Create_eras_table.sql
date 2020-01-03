-- Related to the io.casperlabs.casper.deploybuffer.DeployBufferImpl

CREATE TABLE eras
(
    -- Key block hash or Era ID
    hash                BLOB PRIMARY KEY NOT NULL,
    parent_hash         BLOB,
    -- Since Unix epoch
    start_millis        INTEGER          NOT NULL,
    end_millis          INTEGER          NOT NULL,
    -- The whole era, including the bonds.
    data                BLOB             NOT NULL,
    FOREIGN KEY (hash) REFERENCES block_metadata (block_hash),
    FOREIGN KEY (parent_hash) REFERENCES eras (hash)
);

CREATE INDEX idx_eras_parent_hash ON eras (parent_hash);
CREATE INDEX idx_eras_start_end_millis ON eras (start_millis, end_millis);
