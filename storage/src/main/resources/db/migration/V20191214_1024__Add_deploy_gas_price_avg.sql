-- At the time of this update, we have the price set to a constant 10 in the EE,
-- and the clients are also set to send 10 by default, so it matches up.
ALTER TABLE block_metadata
    ADD COLUMN deploy_gas_price_avg INTEGER NOT NULL DEFAULT 10;
