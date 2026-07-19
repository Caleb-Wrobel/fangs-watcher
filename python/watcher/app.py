"""The HTTP surface: exactly `POST /ping/{token}` and `GET /healthz`."""

import asyncio
import logging
import secrets
from contextlib import asynccontextmanager, suppress

from starlette.applications import Starlette
from starlette.background import BackgroundTask
from starlette.requests import Request
from starlette.responses import PlainTextResponse, Response
from starlette.routing import Route

from .config import Settings
from .core import Watcher
from .notify import Notifier
from .state import State

log = logging.getLogger(__name__)


def build_app(settings: Settings) -> Starlette:
    notifier = Notifier(
        webhook_url=settings.discord_webhook.get_secret_value(),
        healthcheck_url=str(settings.healthcheck_url) if settings.healthcheck_url else None,
    )
    watcher = Watcher(settings, notifier, State.load(settings.state_file))
    expected_token = settings.token.get_secret_value()

    async def ping(request: Request) -> Response:
        """Record a check-in. A wrong token is a 404: the endpoint does not
        admit it exists (SPEC.md § Security)."""
        # Constant-time: the token is a bearer credential, and a timing oracle
        # would let it be guessed a character at a time.
        if not secrets.compare_digest(request.path_params["token"], expected_token):
            return PlainTextResponse("not found", status_code=404)

        # The check-in is durable before we acknowledge it; the recovery notice
        # rides a background task so a slow webhook cannot delay the pulse.
        recovered_at = await watcher.record_ping()
        background = (
            BackgroundTask(watcher.announce_recovery, recovered_at) if recovered_at else None
        )
        return PlainTextResponse("ok", status_code=200, background=background)

    async def healthz(_: Request) -> Response:
        """Our own liveness, for the pod's probe — not the subject's."""
        return PlainTextResponse("ok", status_code=200)

    @asynccontextmanager
    async def lifespan(_: Starlette):
        task = asyncio.create_task(watcher.run_ticker())
        try:
            yield
        finally:
            task.cancel()
            with suppress(asyncio.CancelledError):
                await task
            await notifier.aclose()

    async def not_found(_: Request, __: Exception) -> Response:
        """Answer a method mismatch with 404 rather than Starlette's 405.

        A 405 on `GET /ping/anything` would confirm the route exists whatever
        the token, which is precisely what SPEC.md § Security rules out. An
        unknown path and a known path with the wrong method must be
        indistinguishable.
        """
        return PlainTextResponse("not found", status_code=404)

    return Starlette(
        routes=[
            Route("/ping/{token:path}", ping, methods=["POST"]),
            Route("/healthz", healthz, methods=["GET"]),
        ],
        exception_handlers={405: not_found},
        lifespan=lifespan,
    )
