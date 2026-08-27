-- Two independent availability axes (docs/03-tool-architektur.md):

-- 1) Backend kill-switch, global and runtime-changeable. No row = enabled; only tools that were
-- ever explicitly disabled show up here, so the ~13 existing tools don't need pre-seeding.
CREATE TABLE tool_availability (
    tool_id VARCHAR(50) PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    reason VARCHAR(255),
    updated_at TIMESTAMP NOT NULL
);

-- 2) Client-declared capability, fixed for the channel's lifetime (set once at POST /channels).
CREATE TABLE channel_session_available_tools (
    channel_session_id UUID NOT NULL REFERENCES channel_session(channel_session_id),
    tool_id VARCHAR(50) NOT NULL,
    PRIMARY KEY (channel_session_id, tool_id)
);
