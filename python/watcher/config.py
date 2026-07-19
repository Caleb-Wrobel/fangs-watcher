"""Configuration, per SPEC.md § Configuration — environment only, 12-factor.

Every setting is declared once, with its type and default, and validated at
startup. A bad `WATCHER_PERIOD_SECONDS` should be a refusal to boot, not a
`ValueError` from a ticker thread at 3am.
"""

from pathlib import Path

from pydantic import Field, HttpUrl, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """The `WATCHER_*` environment. Field names map to the variables directly."""

    model_config = SettingsConfigDict(env_prefix="WATCHER_")

    # Secrets are SecretStr so that a stray log line or traceback renders
    # '**********' rather than the credential — SPEC.md forbids either from
    # ever appearing in a log.
    token: SecretStr
    discord_webhook: SecretStr

    subject: str = "the subject"
    period_seconds: int = Field(default=300, gt=0)
    grace_seconds: int = Field(default=900, ge=0)
    state_file: Path = Path("./watcher-state.json")

    # Loopback by default: in production this runs as one container in a
    # `podman kube play` pod, and the networking sidecar sharing the namespace
    # reaches it over 127.0.0.1. Binding all interfaces would publish /ping on
    # the pod IP, bypassing the intended front door. See SPEC.md § Security.
    bind: str = "127.0.0.1"
    port: int = Field(default=8080, gt=0, lt=65536)

    healthcheck_url: HttpUrl | None = None

    @property
    def deadline_seconds(self) -> int:
        """How long a silence may run before it counts as dark (SPEC rule 2)."""
        return self.period_seconds + self.grace_seconds
