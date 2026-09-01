"""Runtime configuration, read from the environment."""

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """
    Service settings.

    Every AI capability has a deterministic fallback, so the service starts and answers
    correctly with no API key and no network. That is what makes the platform demonstrable
    and testable without secrets.
    """

    model_config = SettingsConfigDict(env_prefix="HMS_AI_", env_file=".env", extra="ignore")

    service_name: str = "ai-service"
    port: int = 8000

    # --- authentication -------------------------------------------------------
    # The same JWKS every Java service validates against; this service is one more
    # resource server, not a special case.
    jwks_uri: str = "http://localhost:8081/.well-known/jwks.json"
    jwt_issuer: str = "http://localhost:8081"
    jwt_audience: str = "hms"
    # Only ever enabled for local experimentation; the compose and CI profiles leave it off.
    auth_disabled: bool = False

    # --- Claude API -----------------------------------------------------------
    anthropic_api_key: str | None = None
    # Model used for clinical note summarisation.
    summary_model: str = "claude-opus-5"
    # Effort is deliberately below the default: a clinician is waiting on this response,
    # and summarising a note that is already in front of them is not a hard reasoning task.
    summary_effort: str = "medium"
    summary_max_tokens: int = 4096

    # --- models on disk -------------------------------------------------------
    model_dir: str = "models"
    icd10_data: str = "data/icd10_subset.json"

    @property
    def llm_enabled(self) -> bool:
        """Whether a live model is reachable. When false, every endpoint uses its fallback."""
        return bool(self.anthropic_api_key)


@lru_cache
def get_settings() -> Settings:
    return Settings()
