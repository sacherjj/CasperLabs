-- Going to store the event stream and retrieve them from a certain rowid.
CREATE TABLE events (
    id INTEGER PRIMARY KEY,
    -- Since Unix epoch
    create_time_millis INTEGER NOT NULL,
    value BLOB NOT NULL
);
