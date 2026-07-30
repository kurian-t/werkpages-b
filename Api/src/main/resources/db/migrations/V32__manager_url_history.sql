-- Tracks old (company_slug, manager_slug) pairs so we can 301-redirect
-- when a manager's company changes.
CREATE TABLE manager_url_history (
    id           BIGSERIAL PRIMARY KEY,
    manager_id   BIGINT NOT NULL REFERENCES managers(id) ON DELETE CASCADE,
    company_slug TEXT   NOT NULL,
    manager_slug TEXT   NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX manager_url_history_lookup ON manager_url_history(company_slug, manager_slug);
