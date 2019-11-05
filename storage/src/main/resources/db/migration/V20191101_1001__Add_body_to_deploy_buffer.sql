
-- Add the deploy summary and body to the buffer so we don't have to join.
-- NOTE: The table was created 'without rowid', so it might perform better
-- if it was recreated as a regular table, now that it will hold data. At
-- least it will never hold many rows. Would need to copy all index defs
-- and track down all the alterations made to it.

ALTER TABLE buffered_deploys
    ADD COLUMN summary BLOB;

ALTER TABLE buffered_deploys
    ADD COLUMN body BLOB;

UPDATE buffered_deploys
SET    summary = (SELECT summary FROM deploys d WHERE d.hash = buffered_deploys.hash),
       body    = (SELECT body    FROM deploys d WHERE d.hash = buffered_deploys.hash);
