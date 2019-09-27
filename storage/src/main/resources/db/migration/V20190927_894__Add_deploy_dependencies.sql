CREATE TABLE deploy_headers
(
    hash                 BLOB PRIMARY KEY NOT NULL,
    account              BLOB             NOT NULL,
    timestamp_millis     INTEGER          NOT NULL,
    header               BLOB             NOT NULL,
    FOREIGN KEY (hash) REFERENCES deploys (hash)
) WITHOUT ROWID;

CREATE INDEX idx_deploy_headers_account ON deploy_headers (account);
CREATE INDEX idx_deploy_headers_timestamps_millis ON deploy_headers (timestamp_millis);
