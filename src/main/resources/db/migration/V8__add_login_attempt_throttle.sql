-- Account-level brute-force protection for AUTH tool attempts (docs/04-orchestrierung.md):
-- ToolSession.retryCount alone resets on every fresh tool-activate call, which isn't enough
-- once any device can attempt login against any account (lookup-based login).
CREATE TABLE login_attempt_throttle (
    account_id BIGINT PRIMARY KEY,
    failed_count INT NOT NULL DEFAULT 0,
    locked_until TIMESTAMP,
    updated_at TIMESTAMP NOT NULL
);
