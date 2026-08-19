CREATE TABLE user_resumes (
  id           BIGSERIAL   PRIMARY KEY,
  user_id      UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  template_id  TEXT        NOT NULL DEFAULT 'classic',
  summary      TEXT,
  skills       JSONB       NOT NULL DEFAULT '[]',
  education    JSONB       NOT NULL DEFAULT '[]',
  work_entries JSONB       NOT NULL DEFAULT '[]',
  extra_links  JSONB       NOT NULL DEFAULT '[]',
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX user_resumes_user_id ON user_resumes(user_id);
