"""The role's own durable consumers (PI 5.3, AI 2.7 step 2).

infra/nats/consumers/*.json is the source of truth for provisioning; a test
asserts these definitions equal those files, so code and provisioning cannot drift.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass

from nats.js import JetStreamContext
from nats.js.api import AckPolicy, ConsumerConfig, DeliverPolicy, ReplayPolicy
from nats.js.errors import NotFoundError

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class ConsumerSpec:
    stream: str
    durable: str
    filter_subject: str
    ack_wait_seconds: int
    max_ack_pending: int
    max_deliver: int

    def config(self) -> ConsumerConfig:
        return ConsumerConfig(
            durable_name=self.durable,
            ack_policy=AckPolicy.EXPLICIT,
            ack_wait=float(self.ack_wait_seconds),
            max_ack_pending=self.max_ack_pending,
            max_deliver=self.max_deliver,
            filter_subject=self.filter_subject,
            deliver_policy=DeliverPolicy.ALL,
            replay_policy=ReplayPolicy.INSTANT,
        )


INGEST_UPLOADED = ConsumerSpec("DOCUMENTS", "ingest-uploaded", "documents.uploaded", 60, 8, 5)
INGEST_COMMANDS = ConsumerSpec(
    "DOCUMENTS",
    "ingest-commands",
    "documents.analysis.requested.document_processing",
    60,
    8,
    5,
)
AGENT_EXTRACTION = ConsumerSpec(
    "DOCUMENTS",
    "agent-extraction",
    "documents.analysis.requested.requirement_extraction",
    120,
    2,
    3,
)
AGENT_PRECHECK = ConsumerSpec(
    "DOCUMENTS", "agent-precheck", "documents.analysis.requested.screening_precheck", 120, 2, 3
)
AGENT_EVALUATION = ConsumerSpec(
    "DOCUMENTS", "agent-evaluation", "documents.analysis.requested.evaluation", 120, 2, 3
)

ROLE_CONSUMERS: dict[str, tuple[ConsumerSpec, ...]] = {
    "ingest": (INGEST_UPLOADED, INGEST_COMMANDS),
    "agent": (AGENT_EXTRACTION, AGENT_PRECHECK, AGENT_EVALUATION),
}


async def ensure_consumers(js: JetStreamContext, role: str) -> None:
    """Create the role's consumers if missing. Never edits an existing one."""
    for spec in ROLE_CONSUMERS[role]:
        try:
            await js.consumer_info(spec.stream, spec.durable)
            log.info("consumer exists", extra={"stream": spec.stream, "durable": spec.durable})
        except NotFoundError:
            await js.add_consumer(spec.stream, config=spec.config())
            log.info("consumer created", extra={"stream": spec.stream, "durable": spec.durable})
