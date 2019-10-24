ALTER TABLE block_metadata
    ADD COLUMN deploy_cost_total INTEGER NOT NULL DEFAULT 0;

UPDATE block_metadata
SET    deploy_cost_total = (
  SELECT sum(cost)
  FROM   deploy_process_results r
  WHERE  r.block_hash = block_metadata.block_hash
);
