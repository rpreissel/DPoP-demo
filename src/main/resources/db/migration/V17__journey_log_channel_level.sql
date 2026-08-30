-- Logout with no active journey (the common case: an AUTHENTICATED channel with nothing running)
-- previously left no trace at all in journey_log, since every entry required a journeyId/intent.
-- Both become optional for a channel-level event that isn't part of any journey - see
-- JourneyLogService.recordForChannel.

ALTER TABLE journey_log ALTER COLUMN journey_id SET NULL;
ALTER TABLE journey_log ALTER COLUMN intent SET NULL;
