"""Entrypoint: `python -m watcher`."""

import logging
import sys

import uvicorn
from pydantic import ValidationError

from .app import build_app
from .config import Settings


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
    )

    try:
        settings = Settings()  # type: ignore[call-arg]  # values come from the environment
    except ValidationError as e:
        # Fail loudly at boot rather than midway through an incident. The error
        # names the offending variables but never their values, which may be
        # secrets.
        print("invalid configuration (see SPEC.md § Configuration):", file=sys.stderr)
        for error in e.errors():
            variable = f"WATCHER_{str(error['loc'][0]).upper()}"
            print(f"  {variable}: {error['msg']}", file=sys.stderr)
        return 2

    logging.info(
        "watching %s — listening on %s:%s, state at %s",
        settings.subject,
        settings.bind,
        settings.port,
        settings.state_file,
    )

    uvicorn.run(
        build_app(settings),
        host=settings.bind,
        port=settings.port,
        log_level="warning",  # uvicorn's access log would print the ping token
        access_log=False,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
