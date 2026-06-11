ALTER TABLE managers ADD COLUMN search_created_by_user_id UUID REFERENCES users(id);
