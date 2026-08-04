-- company_stats_live (real table) has replaced the materialized view.
-- refreshCompanyStats() now syncs directly from source tables.
DROP MATERIALIZED VIEW IF EXISTS company_stats CASCADE;
