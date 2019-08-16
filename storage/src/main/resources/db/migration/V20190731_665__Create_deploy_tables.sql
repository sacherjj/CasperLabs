-- Related to the io.casperlabs.casper.deploybuffer.DeployBufferImpl

CREATE TABLE deploys
(
    hash                BLOB PRIMARY KEY NOT NULL,
    account             BLOB             NOT NULL,
    -- Since Unix epoch
    create_time_seconds INTEGER          NOT NULL,
    data                BLOB             NOT NULL
);

CREATE INDEX idx_deploys_account_create_time_hash ON deploys (account, create_time_seconds, hash);

CREATE TABLE buffered_deploys
(
    hash                 BLOB PRIMARY KEY NOT NULL,
    status               INTEGER          NOT NULL,
    account              BLOB             NOT NULL,
    -- Since Unix epoch
    update_time_seconds  INTEGER          NOT NULL,
    -- Needed for expiring deploys preventing disk space overfilling
    -- Since Unix epoch
    receive_time_seconds INTEGER          NOT NULL,
    FOREIGN KEY (hash) REFERENCES deploys (hash)
) WITHOUT ROWID;

-- Useful readings: http://www.sqlitetutorial.net/sqlite-index/
--                  https://www.sqlite.org/queryplanner.html
--                  https://www.sqlite.org/optoverview.html

-- readHashesByStatus
CREATE INDEX idx_buffered_deploys_status_hash ON buffered_deploys (status, hash);

-- readByAccountAndStatus, only 'processed' deploys are filtered by account at the moment, so using partial index
CREATE INDEX idx_buffered_deploys_account_status ON buffered_deploys (account, status) WHERE status = 1;

-- cleanupDiscarded, only 'discarded' deploys affected, so using partial index
CREATE INDEX idx_buffered_deploys_status_update_time ON buffered_deploys (status, update_time_seconds) WHERE status = 2;

-- markAsDiscarded, only 'pending' deploys affected, so using partial index
CREATE INDEX idx_buffered_deploys_status_receive_time ON buffered_deploys (status, receive_time_seconds) WHERE status = 0;
