ALTER TABLE reviews ADD COLUMN draft_token UUID;
CREATE INDEX reviews_draft_token_idx ON reviews (draft_token) WHERE draft_token IS NOT NULL;
