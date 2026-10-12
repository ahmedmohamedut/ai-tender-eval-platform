# Decisions and assumptions

Phase 0 tooling choices, assumptions and known gaps. Newest decisions are added at the end of each section. Design questions that are open in the design documents keep their documented default here and are named by their question number (PI 8, AI 8).

## Tooling

| # | Decision | Reason |
| --- | --- | --- |
| T1 | Java 25 (current LTS) through a Gradle toolchain; Gradle 9.x with Kotlin DSL | Implementation guide 6 fixes no version; solution architecture 3 asks for a current LTS |
| T2 | Spring Boot 4.1.x, WebFlux, R2DBC, Flyway | Reactive backend (PI 1) |
| T3 | Python 3.12 with uv; `uv.lock` and a hash-pinned export for the image | AI 2.6 requires hash pinning |
| T4 | React, TypeScript 5.x, Vite, vitest | `openapi-typescript` needs TypeScript 5 |
| T5 | Root `Makefile` is the single entry point | Command names reserved in AGENTS.md |
| T6 | GitHub Actions for CI | Repository is on GitHub |
| T7 | Caddy as gateway | Native body limit, timeouts and unbuffered streaming |
| T8 | PostgreSQL 18 | `uuidv7()` is built in; meets "15 or later" |

## Assumptions and gaps

Filled in as milestones complete.
