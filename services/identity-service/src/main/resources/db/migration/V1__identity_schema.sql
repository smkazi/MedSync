-- identity schema: who may use the system, and what everyone did.

CREATE TABLE users (
    id                     uuid         PRIMARY KEY,
    username               varchar(64)  NOT NULL UNIQUE,
    email                  varchar(255) NOT NULL UNIQUE,
    password_hash          varchar(255) NOT NULL,
    full_name              varchar(160) NOT NULL,
    active                 boolean      NOT NULL DEFAULT true,
    must_change_password   boolean      NOT NULL DEFAULT false,
    failed_login_attempts  integer      NOT NULL DEFAULT 0,
    locked_until           timestamptz,
    last_login_at          timestamptz,
    version                bigint       NOT NULL DEFAULT 0,
    created_at             timestamptz  NOT NULL DEFAULT now(),
    updated_at             timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE roles (
    id          uuid        PRIMARY KEY,
    code        varchar(32) NOT NULL UNIQUE,
    description varchar(255) NOT NULL,
    version     bigint      NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE user_roles (
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id uuid NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

-- Refresh tokens are stored as SHA-256 hashes: a database leak must not yield usable tokens.
-- family_id ties a rotation chain together so replaying a rotated token can revoke the whole chain.
CREATE TABLE refresh_tokens (
    id           uuid         PRIMARY KEY,
    user_id      uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash   varchar(64)  NOT NULL UNIQUE,
    family_id    uuid         NOT NULL,
    expires_at   timestamptz  NOT NULL,
    revoked_at   timestamptz,
    revoked_reason varchar(64),
    user_agent   varchar(255),
    version      bigint       NOT NULL DEFAULT 0,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);

-- RSA keypairs used to sign access tokens. The public half is served at
-- /.well-known/jwks.json; every other service validates against it and never sees the private half.
CREATE TABLE signing_keys (
    id          uuid        PRIMARY KEY,
    kid         varchar(64) NOT NULL UNIQUE,
    public_pem  text        NOT NULL,
    private_pem text        NOT NULL,
    active      boolean     NOT NULL DEFAULT true,
    retired_at  timestamptz,
    version     bigint      NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- Append-only audit trail, fed by hms.audit.events from every service.
CREATE TABLE audit_log (
    id             uuid         PRIMARY KEY,
    event_id       uuid         NOT NULL UNIQUE,
    service        varchar(64)  NOT NULL,
    action         varchar(64)  NOT NULL,
    entity         varchar(64)  NOT NULL,
    entity_id      varchar(64),
    detail         varchar(1000),
    actor_id       varchar(64),
    username       varchar(64),
    correlation_id varchar(64),
    occurred_at    timestamptz  NOT NULL,
    version        bigint       NOT NULL DEFAULT 0,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_occurred ON audit_log (occurred_at DESC);
CREATE INDEX idx_audit_entity ON audit_log (entity, entity_id);
CREATE INDEX idx_audit_actor ON audit_log (actor_id);

INSERT INTO roles (id, code, description) VALUES
    ('11111111-0000-4000-8000-000000000001', 'ADMIN',        'System administrator: users, roles and configuration'),
    ('11111111-0000-4000-8000-000000000002', 'DOCTOR',       'Physician: full clinical read and write'),
    ('11111111-0000-4000-8000-000000000003', 'NURSE',        'Nursing staff: vitals, triage, clinical notes'),
    ('11111111-0000-4000-8000-000000000004', 'RECEPTIONIST', 'Front desk: registration, booking, check-in'),
    ('11111111-0000-4000-8000-000000000005', 'LAB_TECH',     'Laboratory technician: specimen handling and result entry'),
    ('11111111-0000-4000-8000-000000000006', 'PATHOLOGIST',  'Pathologist: result verification and release');
