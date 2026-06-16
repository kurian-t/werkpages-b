-- Add encrypted PII columns alongside the existing plaintext columns.
-- The app encrypts on all new writes; existing rows are backfilled at startup.
-- Plaintext columns are made nullable to allow the transition period.
-- A future V26 migration will drop them once the backfill is confirmed complete.

ALTER TABLE users
    ADD COLUMN email_encrypted      TEXT,
    ADD COLUMN email_hash           TEXT,
    ADD COLUMN first_name_encrypted TEXT,
    ADD COLUMN last_name_encrypted  TEXT;

-- Make old plaintext columns nullable so new rows can omit them.
ALTER TABLE users
    ALTER COLUMN email      DROP NOT NULL,
    ALTER COLUMN first_name DROP NOT NULL,
    ALTER COLUMN last_name  DROP NOT NULL;

-- Drop the old UNIQUE constraint on email; uniqueness is now enforced via email_hash.
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_email_key;

-- Blind-index unique constraint for email lookups.
CREATE UNIQUE INDEX users_email_hash_idx ON users (email_hash) WHERE email_hash IS NOT NULL;
