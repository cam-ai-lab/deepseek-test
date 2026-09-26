-- Runs once, when the PostgreSQL container is first created - before Flyway, because Flyway runs
-- later, from the application inside the app container.
--
-- The black-box suite asserts on stored state through this role. It can only read. That is enforced
-- by PostgreSQL rather than by convention: a step that tries to write fails at the database level,
-- so "SQL only for assertions" cannot quietly erode.

CREATE ROLE blackbox_reader LOGIN PASSWORD 'blackbox_reader';

GRANT CONNECT ON DATABASE quotes TO blackbox_reader;
GRANT USAGE ON SCHEMA public TO blackbox_reader;

-- The important line. Flyway creates the tables later, as the `quotes` role, so a plain GRANT would
-- be too early and would miss them. Default privileges apply to objects created in the future.
ALTER DEFAULT PRIVILEGES FOR ROLE quotes IN SCHEMA public
    GRANT SELECT ON TABLES TO blackbox_reader;
