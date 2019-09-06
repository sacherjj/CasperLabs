CREATE TABLE deploy_account_nonce
(
    hash    BLOB    PRIMARY KEY NOT NULL,
    account BLOB                NOT NULL,
    nonce   INTEGER             NOT NULL,
    FOREIGN KEY (hash) REFERENCES deploys (hash)
);

CREATE INDEX idx_deploys_account_nonce ON deploy_account_nonce (account, nonce);
