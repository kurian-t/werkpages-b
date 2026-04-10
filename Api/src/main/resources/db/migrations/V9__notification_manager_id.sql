-- Add optional manager reference to notifications so the frontend can link to the manager profile.
ALTER TABLE notifications ADD COLUMN manager_id BIGINT REFERENCES managers(id) ON DELETE SET NULL;
