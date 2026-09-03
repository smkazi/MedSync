# Common tasks. Every target is a thin wrapper over the underlying tool, so nothing here hides
# what is actually being run.

.DEFAULT_GOAL := help
SHELL := /bin/bash

.PHONY: help
help: ## Show this help
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

# ---- build and run -----------------------------------------------------------

.PHONY: build
build: ## Build every Java module
	mvn -q package -DskipTests

.PHONY: up
up: ## Start the whole platform in containers
	docker compose up --build -d

.PHONY: down
down: ## Stop the containers
	docker compose down

.PHONY: dev
dev: build ## Run the Java services natively against a local Postgres
	scripts/local.sh start

# The gateway's rate limiter and a load test want opposite things. The test targets below need a
# stack started with the limits raised; this variable is what to start it with.
#
#   HMS_RATE_LIMIT_AUTH_RPM=5000 HMS_RATE_LIMIT_RPM=100000 HMS_RATE_LIMIT_PORTAL_RPM=100000 make dev
#
# The limiter itself is covered by EdgeFilterTest in the gateway module, so raising it for a load
# run costs no coverage.
TEST_RATE_LIMITS = HMS_RATE_LIMIT_AUTH_RPM=5000 HMS_RATE_LIMIT_RPM=100000 HMS_RATE_LIMIT_PORTAL_RPM=100000

.PHONY: dev-test-stack
dev-test-stack: build ## Run the stack with rate limits raised, for the API and perf suites
	$(TEST_RATE_LIMITS) scripts/local.sh start

.PHONY: dev-stop
dev-stop: ## Stop the natively-run services
	scripts/local.sh stop

# Splits the one database superuser into hms_migrate (owns the schemas, holds DDL) and hms_app
# (read and write the data, no DDL). Run once, as a superuser; idempotent, so running it again
# after adding a service grants the new schema. Passwords come from the environment so none is
# committed -- the defaults in the script match the development credentials and are useless
# anywhere else.
.PHONY: db-roles
db-roles: ## Create the hms_migrate and hms_app database roles (run once, as a superuser)
	psql "$${HMS_DB_ADMIN_URL:-postgresql://hms:hms@localhost:5432/hms}" -f scripts/db-roles.sql \
	     -v migrate_password="$${HMS_DB_MIGRATION_PASSWORD:-hms}" \
	     -v app_password="$${HMS_DB_PASSWORD:-hms}"

# ---- tests -------------------------------------------------------------------

.PHONY: test
test: test-java test-python ## Run every unit and integration suite

.PHONY: test-java
test-java: ## Java unit and integration tests (needs PostgreSQL)
	mvn -q verify

.PHONY: test-python
test-python: ## ai-service tests
	cd services/ai-service && uv run pytest -q

.PHONY: test-web
test-web: ## Web static checks
	cd web && npm run lint && npm run typecheck

.PHONY: test-e2e
test-e2e: ## Browser end-to-end tests (needs the stack running)
	cd web && npx playwright test

.PHONY: test-api
test-api: ## Cross-service API journeys (needs the stack running)
	mvn -q -pl tests/api verify -Pautomation

# ---- security ----------------------------------------------------------------

.PHONY: security
security: sast sca secrets ## Every static security check

.PHONY: sast
sast: ## Static analysis: SpotBugs + FindSecBugs, Bandit, ESLint security rules
	mvn -q -Pquality verify -DskipTests
	cd services/ai-service && uv run bandit -q -r app training && uv run ruff check .
	cd web && npm run lint

.PHONY: sbom
sbom: ## CycloneDX SBOM for the whole reactor
	mvn -q -Psbom package -DskipTests
	@echo "SBOM: target/classes/META-INF/sbom/application.cdx.json"

.PHONY: sca
sca: sbom ## Dependency vulnerability scanning
	# Trivy over the SBOM is the Java gate: no NVD API key, seconds rather than hours. Install it
	# from https://trivy.dev if this fails with "command not found".
	trivy sbom --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 \
		target/classes/META-INF/sbom/application.cdx.json
	cd services/ai-service && uv run --with pip-audit pip-audit
	cd web && npm audit --audit-level=high
	# OWASP Dependency-Check as well, if you have a key. It is not in the line above because
	# without NVD_API_KEY the feed download is throttled to the point of being useless, and
	# passing an empty key is an outright error rather than a graceful degradation:
	#
	#   NVD_API_KEY=... mvn -q -Psecurity verify -DskipTests -DnvdApiKey="$$NVD_API_KEY"

.PHONY: secrets
secrets: ## Scan the working tree and history for secrets
	gitleaks detect --no-banner --redact --verbose

.PHONY: vapt
vapt: ## Authenticated OWASP ZAP scan (needs the stack running)
	security/zap/run.sh

.PHONY: pentest
pentest: ## Targeted penetration checks (needs the stack running)
	security/pentest/run.sh

# ---- performance -------------------------------------------------------------

.PHONY: perf-smoke
perf-smoke: ## k6 smoke profile
	k6 run tests/perf/smoke.js

.PHONY: perf-load
perf-load: ## k6 load profile
	k6 run tests/perf/load.js

.PHONY: perf-stress
perf-stress: ## k6 stress profile
	k6 run tests/perf/stress.js

.PHONY: perf-soak
perf-soak: ## k6 soak profile
	k6 run tests/perf/soak.js

# ---- certificates ------------------------------------------------------------

.PHONY: certs
certs: ## Generate development TLS certificates
	scripts/gen-certs.sh
