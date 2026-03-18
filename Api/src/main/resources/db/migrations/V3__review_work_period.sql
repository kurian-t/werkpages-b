-- Add reviewer's work period to reviews
ALTER TABLE reviews ADD COLUMN worked_from DATE;
ALTER TABLE reviews ADD COLUMN worked_until DATE;

CREATE INDEX idx_reviews_worked_from ON reviews(worked_from);
