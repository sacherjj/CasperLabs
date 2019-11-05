DROP TABLE deploy_headers;

-- Keep the body of the deploy separate so we can retrieve just the summary.
ALTER TABLE deploys
    ADD COLUMN body BLOB NOT NULL DEFAULT x'';

-- Use the existing data for summaries, we'll deal with the fallback where body is empty.
ALTER TABLE deploys
    RENAME COLUMN data TO summary;
