CREATE TABLE deploy_headers
(
    hash                 BLOB PRIMARY KEY NOT NULL,
    account              BLOB             NOT NULL,
    timestamp_millis     INTEGER          NOT NULL,
    header               BLOB             NOT NULL,
    FOREIGN KEY (hash) REFERENCES deploys (hash)
);

CREATE INDEX idx_deploy_headers_account_timestamps_millis ON deploy_headers (account, timestamp_millis);
