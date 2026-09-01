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

.PHONY: dev-stop
dev-stop: ## Stop the natively-run services
	scripts/local.sh stop

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

.PHONY: sca
sca: ## Dependency vulnerability scanning
	mvn -q -Psecurity org.owasp:dependency-check-maven:check
	cd services/ai-service && uv run --with pip-audit pip-audit
	cd web && npm audit --audit-level=high

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
