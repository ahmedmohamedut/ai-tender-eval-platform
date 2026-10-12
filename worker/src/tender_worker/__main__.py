"""Entry point: `python -m tender_worker`. WORKER_ROLE chooses the consumers."""

from __future__ import annotations

import asyncio

from tender_worker.logging_setup import configure_logging
from tender_worker.runner import Runner
from tender_worker.settings import load_settings


def main() -> None:
    settings = load_settings()
    configure_logging(settings.LOG_LEVEL, settings.WORKER_ROLE, settings.WORKER_ID)
    asyncio.run(Runner(settings).run())


if __name__ == "__main__":
    main()
