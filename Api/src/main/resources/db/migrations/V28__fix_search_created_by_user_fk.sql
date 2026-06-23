-- V22 added search_created_by_user_id without ON DELETE SET NULL, blocking account deletion.
ALTER TABLE managers DROP CONSTRAINT IF EXISTS managers_search_created_by_user_id_fkey;
ALTER TABLE managers ADD CONSTRAINT managers_search_created_by_user_id_fkey
    FOREIGN KEY (search_created_by_user_id) REFERENCES users(id) ON DELETE SET NULL;
