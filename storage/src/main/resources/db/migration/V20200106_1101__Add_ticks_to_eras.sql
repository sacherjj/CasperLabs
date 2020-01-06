
-- Add ticks to the eras to be able to tell when a message is after the end
-- without having to retrieve the full era record.
ALTER TABLE eras ADD start_tick INTEGER NOT NULL DEFAULT 0;
ALTER TABLE eras ADD end_tick INTEGER NOT NULL DEFAULT 0;

UPDATE eras SET start_tick = start_millis, end_tick = end_millis;
