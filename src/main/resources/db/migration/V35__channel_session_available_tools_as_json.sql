-- B6: channel_session.availableClientTools was an @ElementCollection(fetch = EAGER) backed by
-- its own table, forcing a join/extra query on the hottest path of the system for a value that
-- is constant over the channel's entire lifetime. Moved onto channel_session as a JSON column,
-- same pattern as account.identifications/authentication_methods (V1__schema.sql).

ALTER TABLE channel_session ADD COLUMN available_tools JSON NOT NULL DEFAULT '[]';

UPDATE channel_session cs SET available_tools = CAST(COALESCE((
    SELECT '[' || LISTAGG('"' || t.tool_id || '"', ',') || ']'
    FROM channel_session_available_tools t
    WHERE t.channel_session_id = cs.channel_session_id
), '[]') AS JSON);

DROP TABLE channel_session_available_tools;
