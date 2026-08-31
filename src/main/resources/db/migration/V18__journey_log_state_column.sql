-- JourneyState (the position on the path when an event happened) was only ever tucked into the
-- detail JSON blob, indistinguishable from any other diagnostic key-value. It's first-class
-- information like event_type, not a detail - promoted to its own column.

ALTER TABLE journey_log ADD COLUMN journey_state VARCHAR(100);
