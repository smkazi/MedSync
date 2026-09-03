-- Split the platform's one database superuser into roles by job.
--
-- Run once, as a superuser, against the platform database:
--
--     psql -d hms -f scripts/db-roles.sql \
--          -v migrate_password="$HMS_DB_MIGRATION_PASSWORD" \
--          -v app_password="$HMS_DB_PASSWORD"
--
-- Idempotent: run it again after adding a service and it grants the new schema without disturbing
-- anything already in place.
--
-- ---------------------------------------------------------------------------
-- Why this exists
-- ---------------------------------------------------------------------------
-- Until now one superuser did three jobs: install extensions, run every migration, and serve every
-- runtime query for all nine schemas. That means a SQL-injection hole in any one service reaches
-- every other service's tables, and a Hibernate mapping mistake can drop one.
--
-- Three jobs, so the split is by job:
--
--   * Bootstrap -- installing pg_trgm and btree_gist -- genuinely needs a superuser, and is not a
--     role. It is this script, run by a person, once. Creating an `hms_bootstrap` superuser role
--     and then telling everybody not to use it would add a credential without removing one.
--   * hms_migrate owns every schema and holds DDL. Flyway's credential, and only Flyway's.
--   * hms_app has USAGE on the schemas, DML on their tables, USAGE on their sequences, and no DDL
--     at all. The runtime credential -- the one in nine services' environments.
--
-- What this does NOT do, stated plainly rather than implied: it does not isolate one service's
-- tables from another's. All nine share hms_app. Per-service runtime roles need nine credentials in
-- nine environments and this platform ships as one compose file with one database; that is a named
-- gap in the README, not something quietly claimed here. What the split does remove is DDL and the
-- superuser from the request path, which is the larger half.

\set ON_ERROR_STOP on

-- Passwords come from the command line so none is committed. Defaults match the development
-- credentials the README sets up, and are useless anywhere else.
\if :{?migrate_password}
\else
  \set migrate_password 'hms'
\endif
\if :{?app_password}
\else
  \set app_password 'hms'
\endif

-- ---------------------------------------------------------------------------
-- Extensions, installed once by the superuser running this
-- ---------------------------------------------------------------------------
-- patient V1 needs pg_trgm and scheduling V1 needs btree_gist, and both migrations say
-- CREATE EXTENSION IF NOT EXISTS. That form succeeds for an unprivileged role when the extension is
-- already present -- it skips before the privilege check -- so installing them here is what lets
-- those migrations keep running unchanged under hms_migrate.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ---------------------------------------------------------------------------
-- The two roles
-- ---------------------------------------------------------------------------
-- Created with \gexec rather than inside a DO block, and not by preference: psql does not
-- interpolate :variables inside dollar-quoted strings, so a password passed on the command line
-- would arrive at PostgreSQL as the literal text ":'migrate_password'".
SELECT 'CREATE ROLE hms_migrate LOGIN'
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'hms_migrate')
\gexec
SELECT 'CREATE ROLE hms_app LOGIN'
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'hms_app')
\gexec

-- Set every time, so re-running this script rotates the passwords rather than silently keeping
-- whatever was there.
SELECT format('ALTER ROLE hms_migrate PASSWORD %L', :'migrate_password')
\gexec
SELECT format('ALTER ROLE hms_app PASSWORD %L', :'app_password')
\gexec

-- Neither role may create a database, create a role, or bypass row security. Stated rather than
-- assumed: a CREATE ROLE with no options already has none of these, and an ALTER of an existing
-- role that somebody had granted them would silently keep them.
ALTER ROLE hms_migrate NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
ALTER ROLE hms_app NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;

GRANT CONNECT ON DATABASE :"DBNAME" TO hms_migrate, hms_app;

-- ---------------------------------------------------------------------------
-- One schema per service, owned by hms_migrate
-- ---------------------------------------------------------------------------
-- Pre-created here rather than left to Flyway's create-schemas, so hms_migrate never needs CREATE
-- on the database itself. Adding a service means adding one name to this list.
DO $$
DECLARE
    service_schema text;
BEGIN
    FOREACH service_schema IN ARRAY ARRAY[
        'identity', 'patient', 'scheduling', 'laboratory', 'notification',
        'admissions', 'pharmacy', 'billing', 'interop'
    ]
    LOOP
        EXECUTE format('CREATE SCHEMA IF NOT EXISTS %I AUTHORIZATION hms_migrate', service_schema);
        EXECUTE format('ALTER SCHEMA %I OWNER TO hms_migrate', service_schema);

        -- The runtime role: read and write the data, and nothing structural. No CREATE, so a
        -- mapping bug cannot add a table and an injected statement cannot drop one.
        EXECUTE format('GRANT USAGE ON SCHEMA %I TO hms_app', service_schema);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO hms_app',
                       service_schema);
        EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO hms_app',
                       service_schema);

        -- And the same for whatever a future migration creates. Without this, every new table
        -- would be invisible to the runtime until somebody remembered to re-run this script --
        -- which is the kind of thing remembered the second time.
        EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE hms_migrate IN SCHEMA %I '
                       'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO hms_app', service_schema);
        EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE hms_migrate IN SCHEMA %I '
                       'GRANT USAGE, SELECT ON SEQUENCES TO hms_app', service_schema);
    END LOOP;
END
$$;

-- ---------------------------------------------------------------------------
-- Hand over what already exists
-- ---------------------------------------------------------------------------
-- On a database that has been running, every table was created by the old superuser and is still
-- owned by it. Owning the schema is not owning its tables: hms_migrate could create new ones and
-- would be refused an ALTER or a DROP on any of these, so the next migration to touch an existing
-- table would fail with "must be owner of table" -- after the service had already started. Found by
-- trying it, not by reasoning about it.
--
-- On a fresh database this loop matches nothing and costs nothing.
DO $$
DECLARE
    obj record;
BEGIN
    FOR obj IN
        SELECT 'TABLE' AS kind, format('%I.%I', schemaname, tablename) AS name
          FROM pg_tables
         WHERE schemaname IN ('identity', 'patient', 'scheduling', 'laboratory', 'notification',
                              'admissions', 'pharmacy', 'billing', 'interop')
           AND tableowner <> 'hms_migrate'
        UNION ALL
        SELECT 'SEQUENCE', format('%I.%I', sequence_schema, sequence_name)
          FROM information_schema.sequences
         WHERE sequence_schema IN ('identity', 'patient', 'scheduling', 'laboratory', 'notification',
                                   'admissions', 'pharmacy', 'billing', 'interop')
        UNION ALL
        SELECT 'VIEW', format('%I.%I', schemaname, viewname)
          FROM pg_views
         WHERE schemaname IN ('identity', 'patient', 'scheduling', 'laboratory', 'notification',
                              'admissions', 'pharmacy', 'billing', 'interop')
           AND viewowner <> 'hms_migrate'
    LOOP
        EXECUTE format('ALTER %s %s OWNER TO hms_migrate', obj.kind, obj.name);
    END LOOP;
END
$$;

-- ---------------------------------------------------------------------------
-- public: usable, not writable
-- ---------------------------------------------------------------------------
-- Every table on this platform lives in a named schema, so nobody needs to create anything in
-- public -- and a writable public schema is where an injected CREATE TABLE would go. But USAGE has
-- to stay: pg_trgm and btree_gist install their operator classes there, so revoking it outright
-- means patient V1's `USING gin (... gin_trgm_ops)` fails with "operator class does not exist for
-- access method gin" on a fresh database. Found by running this against an empty one, which is the
-- only way it would have been found -- on an existing database every index already exists and the
-- migration is a no-op.
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO hms_migrate, hms_app;

\echo 'hms_migrate and hms_app are ready. Set HMS_DB_USER/HMS_DB_PASSWORD to hms_app and'
\echo 'HMS_DB_MIGRATION_USER/HMS_DB_MIGRATION_PASSWORD to hms_migrate, then start the services.'
