CREATE TABLE deploys
(
    hash    BLOB PRIMARY KEY NOT NULL,
    account BLOB             NOT NULL,
    data    BLOB             NOT NULL
);

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
    FOREIGN KEY (hash) REFERENCES deploys (hash),
    FOREIGN KEY (account) REFERENCES deploys (account)
) WITHOUT ROWID;

-- Useful readings: http://www.sqlitetutorial.net/sqlite-index/
--                  https://www.sqlite.org/queryplanner.html
--                  https://www.sqlite.org/optoverview.html
CREATE INDEX idx_buffered_deploys_status ON buffered_deploys (status);
CREATE INDEX idx_buffered_deploys_account_status ON buffered_deploys (account, status);
CREATE INDEX idx_buffered_deploys_hash_status ON buffered_deploys (hash, status);
CREATE INDEX idx_buffered_deploys_status_update_time ON buffered_deploys (status, update_time_seconds);
CREATE INDEX idx_buffered_deploys_hash_status_update_time ON buffered_deploys (hash, status, update_time_seconds);
CREATE INDEX idx_buffered_deploys_status_receive_time ON buffered_deploys (status, receive_time_seconds);
CREATE INDEX idx_deploys_status_data ON deploys (data);
