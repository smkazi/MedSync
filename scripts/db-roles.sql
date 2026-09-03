-- Split the platform's one database superuser into roles by job.
--
-- Run once, as a superuser, against the platform database:
--
--     psql -d hms -f scripts/db-roles.sql \
--          -v migrate_password="$HMS_DB_MIGRATION_PASSWORD" \
--          -v app_password="$HMS_DB_PASSWORD" \
--          -v identity_password="$HMS_DB_IDENTITY_PASSWORD" \
--          ... one -v per service, see the list below
--
-- Any per-service password left unset falls back to app_password, and the script says so loudly at
-- the end. That fallback is a development convenience and nothing more: nine roles sharing one
-- password are nine sets of privileges behind one secret, so a leaked credential still opens every
-- schema. The privilege split survives it; the credential split does not.
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
--   * One runtime role per service -- hms_identity, hms_patient, and so on -- with USAGE on its own
--     schema, DML on that schema's tables, USAGE on its sequences, and no DDL at all. Nine
--     credentials for nine services, which is the point: a SQL-injection hole in pharmacy-service
--     reaches pharmacy's tables and is refused on patient's, because the role it is connected as
--     has no grant there. One shared runtime role made that hole reach all nine.
--   * hms_app keeps the old shape -- DML on every schema -- for a deployment that has not split its
--     credentials yet. It is the fallback the compose file and scripts/local.sh use when the
--     per-service variables are unset, so an unconfigured stack still starts. A deployment using it
--     has the DDL split and not the isolation, which the README says in those words.
--
-- What this still does NOT do: the nine runtime roles share one *migration* role. Flyway owns every
-- schema as hms_migrate, so a compromised migration credential reaches everything. That is a
-- deliberate trade rather than an oversight -- migrations run at startup from files in the image,
-- not from anything a request can influence, so the injection surface this script exists to close
-- is not there. Splitting it would add eight credentials to close a door nothing opens.

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

-- One per service. Unset means "use app_password", which is the development shape; the closing
-- message names every service still sharing it so nobody has to diff this file to find out.
\if :{?identity_password}
\else
  \set identity_password :app_password
\endif
\if :{?patient_password}
\else
  \set patient_password :app_password
\endif
\if :{?scheduling_password}
\else
  \set scheduling_password :app_password
\endif
\if :{?laboratory_password}
\else
  \set laboratory_password :app_password
\endif
\if :{?notification_password}
\else
  \set notification_password :app_password
\endif
\if :{?admissions_password}
\else
  \set admissions_password :app_password
\endif
\if :{?pharmacy_password}
\else
  \set pharmacy_password :app_password
\endif
\if :{?billing_password}
\else
  \set billing_password :app_password
\endif
\if :{?interop_password}
\else
  \set interop_password :app_password
\endif
\if :{?imaging_password}
\else
  \set imaging_password :app_password
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

-- The nine runtime roles. Named for the schema each one owns the data in, so a connection in
-- pg_stat_activity says which service it belongs to without anybody cross-referencing a port.
SELECT format('CREATE ROLE %I LOGIN', role)
  FROM (VALUES
           ('hms_identity'),
           ('hms_patient'),
           ('hms_scheduling'),
           ('hms_laboratory'),
           ('hms_notification'),
           ('hms_admissions'),
           ('hms_pharmacy'),
           ('hms_billing'),
           ('hms_interop'),
           ('hms_imaging')
       ) AS t(role)
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = t.role)
\gexec

-- Set every time, so re-running this script rotates the passwords rather than silently keeping
-- whatever was there.
SELECT format('ALTER ROLE hms_migrate PASSWORD %L', :'migrate_password')
\gexec
SELECT format('ALTER ROLE hms_app PASSWORD %L', :'app_password')
\gexec

SELECT format('ALTER ROLE %I PASSWORD %L', role, pw)
  FROM (VALUES
           ('hms_identity', :'identity_password'),
           ('hms_patient', :'patient_password'),
           ('hms_scheduling', :'scheduling_password'),
           ('hms_laboratory', :'laboratory_password'),
           ('hms_notification', :'notification_password'),
           ('hms_admissions', :'admissions_password'),
           ('hms_pharmacy', :'pharmacy_password'),
           ('hms_billing', :'billing_password'),
           ('hms_interop', :'interop_password'),
           ('hms_imaging', :'imaging_password')
       ) AS t(role, pw)
\gexec

-- Neither role may create a database, create a role, or bypass row security. Stated rather than
-- assumed: a CREATE ROLE with no options already has none of these, and an ALTER of an existing
-- role that somebody had granted them would silently keep them.
ALTER ROLE hms_migrate NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
ALTER ROLE hms_app NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;

SELECT format('ALTER ROLE %I NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS', role)
  FROM (VALUES
           ('hms_identity'),
           ('hms_patient'),
           ('hms_scheduling'),
           ('hms_laboratory'),
           ('hms_notification'),
           ('hms_admissions'),
           ('hms_pharmacy'),
           ('hms_billing'),
           ('hms_interop'),
           ('hms_imaging')
       ) AS t(role)
\gexec

GRANT CONNECT ON DATABASE :"DBNAME" TO hms_migrate, hms_app;

SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), role)
  FROM (VALUES
           ('hms_identity'),
           ('hms_patient'),
           ('hms_scheduling'),
           ('hms_laboratory'),
           ('hms_notification'),
           ('hms_admissions'),
           ('hms_pharmacy'),
           ('hms_billing'),
           ('hms_interop'),
           ('hms_imaging')
       ) AS t(role)
\gexec

-- ---------------------------------------------------------------------------
-- One schema per service, owned by hms_migrate
-- ---------------------------------------------------------------------------
-- Pre-created here rather than left to Flyway's create-schemas, so hms_migrate never needs CREATE
-- on the database itself. Adding a service means adding one name to this list.
DO $$
DECLARE
    service_schema text;
    service_role text;
BEGIN
    FOREACH service_schema IN ARRAY ARRAY[
        'identity', 'patient', 'scheduling', 'laboratory', 'notification',
        'admissions', 'pharmacy', 'billing', 'interop', 'imaging'
    ]
    LOOP
        EXECUTE format('CREATE SCHEMA IF NOT EXISTS %I AUTHORIZATION hms_migrate', service_schema);
        EXECUTE format('ALTER SCHEMA %I OWNER TO hms_migrate', service_schema);

        -- The shared runtime role: read and write the data, and nothing structural. No CREATE, so
        -- a mapping bug cannot add a table and an injected statement cannot drop one. Every schema,
        -- which is exactly the isolation the per-service role below adds.
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

        -- The service's own role, granted on this schema and on no other. `hms_billing` gets
        -- billing and nothing else, so an injected `SELECT ... FROM patient.patients` in
        -- billing-service is refused by PostgreSQL rather than by billing-service remembering not
        -- to ask. Note what is *not* granted anywhere: no USAGE on another service's schema, which
        -- is what makes the refusal "permission denied for schema patient" rather than a row.
        service_role := format('hms_%s', service_schema);
        EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', service_schema, service_role);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO %I',
                       service_schema, service_role);
        EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO %I',
                       service_schema, service_role);
        EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE hms_migrate IN SCHEMA %I '
                       'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I',
                       service_schema, service_role);
        EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE hms_migrate IN SCHEMA %I '
                       'GRANT USAGE, SELECT ON SEQUENCES TO %I', service_schema, service_role);

        -- Revoked rather than never granted, because this script is idempotent and is expected to
        -- be re-run on a database where an earlier version handed a service more than it needs.
        -- A grant that was correct once and is wrong now has to be taken away by something, and
        -- the only thing that runs again is this.
        EXECUTE format('REVOKE ALL ON SCHEMA %I FROM PUBLIC', service_schema);
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
                              'admissions', 'pharmacy', 'billing', 'interop', 'imaging')
           AND tableowner <> 'hms_migrate'
        UNION ALL
        SELECT 'SEQUENCE', format('%I.%I', sequence_schema, sequence_name)
          FROM information_schema.sequences
         WHERE sequence_schema IN ('identity', 'patient', 'scheduling', 'laboratory', 'notification',
                                   'admissions', 'pharmacy', 'billing', 'interop', 'imaging')
        UNION ALL
        SELECT 'VIEW', format('%I.%I', schemaname, viewname)
          FROM pg_views
         WHERE schemaname IN ('identity', 'patient', 'scheduling', 'laboratory', 'notification',
                              'admissions', 'pharmacy', 'billing', 'interop', 'imaging')
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

SELECT format('GRANT USAGE ON SCHEMA public TO %I', role)
  FROM (VALUES
           ('hms_identity'),
           ('hms_patient'),
           ('hms_scheduling'),
           ('hms_laboratory'),
           ('hms_notification'),
           ('hms_admissions'),
           ('hms_pharmacy'),
           ('hms_billing'),
           ('hms_interop'),
           ('hms_imaging')
       ) AS t(role)
\gexec

-- ---------------------------------------------------------------------------
-- What just happened
-- ---------------------------------------------------------------------------
-- Every service still on app_password is named, because "nine roles" reads like isolation and a
-- shared password is not it. Silence here would be the script agreeing with a claim the deployment
-- has not earned.
SELECT CASE count(*)
         WHEN 0 THEN 'Every service has its own password.'
         ELSE format('%s service(s) still share app_password: %s. '
                     'Set the per-service -v variables to separate them.',
                     count(*), string_agg(service, ', ' ORDER BY service))
       END AS "credential separation"
  FROM (VALUES
           ('identity', :'identity_password'),
           ('patient', :'patient_password'),
           ('scheduling', :'scheduling_password'),
           ('laboratory', :'laboratory_password'),
           ('notification', :'notification_password'),
           ('admissions', :'admissions_password'),
           ('pharmacy', :'pharmacy_password'),
           ('billing', :'billing_password'),
           ('interop', :'interop_password'),
           ('imaging', :'imaging_password')
       ) AS t(service, pw)
 WHERE pw = :'app_password';

\echo ''
\echo 'Roles are ready:'
\echo '  hms_migrate  -- HMS_DB_MIGRATION_USER/PASSWORD, owns the schemas, runs Flyway'
\echo '  hms_<service> -- HMS_DB_<SERVICE>_USER/PASSWORD, one schema each, no DDL'
\echo '  hms_app      -- HMS_DB_USER/PASSWORD, every schema: the fallback, not the goal'
\echo ''
\echo 'docker-compose.yml and scripts/local.sh already prefer the per-service variables and fall'
\echo 'back to HMS_DB_USER when they are unset, so setting them is the whole migration.'
