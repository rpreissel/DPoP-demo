CREATE TABLE registration_order_setting (
    id VARCHAR(50) PRIMARY KEY,
    enroll_first BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMP NOT NULL
);
