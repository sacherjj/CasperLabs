CREATE TABLE deploy_process_results
(
    block_hash              BLOB    NOT NULL,
    deploy_hash             BLOB    NOT NULL,
    -- Index of deploy in block to restore block precisely
    deploy_position         INTEGER NOT NULL,
    account                 BLOB    NOT NULL,
    -- Since Unix epoch
    create_time_millis      INTEGER NOT NULL,
    -- Since Unix epoch
    execute_time_millis     INTEGER NOT NULL,
    cost                    INTEGER NOT NULL,
    execution_error_message TEXT,
    PRIMARY KEY (block_hash, deploy_position),
    FOREIGN KEY (deploy_hash) REFERENCES deploys (hash)
) WITHOUT ROWID;

CREATE UNIQUE INDEX idx_deploy_process_results_deploy_hash ON deploy_process_results (deploy_hash, block_hash);

ALTER TABLE buffered_deploys
    ADD COLUMN status_message TEXT;

ALTER TABLE deploys
    RENAME COLUMN create_time_seconds TO create_time_millis;
ALTER TABLE buffered_deploys
    RENAME COLUMN update_time_seconds TO update_time_millis;
ALTER TABLE buffered_deploys
    RENAME COLUMN receive_time_seconds TO receive_time_millis;

UPDATE deploys
SET create_time_millis=create_time_millis * 1000;
UPDATE buffered_deploys
SET update_time_millis=update_time_millis * 1000;
UPDATE buffered_deploys
SET receive_time_millis=receive_time_millis * 1000;

