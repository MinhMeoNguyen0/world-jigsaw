# Project handoff

This describes the private-local prototype on the merged baseline, not a production deployment.
The [README](../README.md) owns setup and check commands. The [demo notes](demo/README.md) describe
what was recorded and how to reproduce the exercise with disposable data.

## Architecture and behavior

The React/TypeScript Vite client renders the puzzle and talks through its local development proxy
to two independent Java services. The Spring Boot game service owns the PostgreSQL game database,
sessions, canonical board, turn coordination and validation. HTTP opens and settles turns and reads
the current board; a WebSocket distributes acknowledged transient previews, committed events and
best-effort cursor presence. `packages/contracts` defines the shared wire and fixtures;
`packages/generator` makes definitions and initial snapshots; `packages/rules` contains local draft
rules. The client uses server acknowledgments and resynchronizes when state is uncertain.

A player can preview a batch of up to five charged actions; moving a connected group counts as one.
Other players see accepted previews before confirmation. Confirm atomically persists the board,
quota, event and command result; cancel discards the draft, and unused allowance remains. Confirm
retries must use the same command ID and payload. Drafts and cursor presence are ephemeral; confirmed
state survives restart. See the [turn wire](../services/backend/TURN-WIRE.md),
[confirmation journal](../services/backend/CONFIRMATION-JOURNAL.md),
[geometry notes](../services/backend/LOCAL-GEOMETRY.md), and
[preview assessment](multiplayer-preview-assessment.md) for boundaries and failure handling.

The separate chat relay checks the game session and uses its own PostgreSQL database for validation
settings. Messages fan out over SSE and are not persisted; reconnect starts empty and delivery is not
guaranteed. See the [chat service](../services/chat/README.md) and
[client chat notes](../apps/web/src/chat/README.md). The backend's
[preview demo](../services/backend/PREVIEW-INTEGRATION.md) is a separate demonstration, not the
running game service.

## Running, testing and operations

Follow the [local setup, board import and checks](../README.md#local-setup) in the README, and the
[chat setup](../services/chat/README.md). Import a fixture for the current UTC day into a fresh game
database, then start the backend, chat relay and web client. Keep the game and chat databases
separate. The integration tests mutate their board; use fresh disposable databases, never a board or
backup to be retained. Backend integration tests use Testcontainers and need Docker. Local checks and
remote CI are separate evidence; a passing browser demo does not prove the full test suite passed.

The game is a single-writer prototype: all backends for a database must share its documented local
file-lock and PostgreSQL advisory-lock boundary. Stop the existing writer before replacing it; do not
remove its lock file while it runs. Confirmed data and receipts persist; unsaved drafts do not.
Back up and verify restoration before any migration or replacement. Existing live preview processes,
databases and backups are operational data, not test fixtures. Their custody and teardown need an
explicit owner; a transient local service is not a durable deployment.

On the 7.6 GiB development host, browser, recording and JVM workloads have caused memory pressure.
Coordinate one heavy workload at a time, bound workers, and monitor available memory, swap and memory
pressure throughout. Stop only the workload you own if available memory falls below 2 GiB or swap
usage rises; do not stop a shared preview or its database to make room. These are host-specific
operational constraints, not application requirements.

## Gaps and document map

- Local loopback identity and service boundaries are prototypes. Public authentication, TLS/proxy
  setup, authorization, abuse controls, chat moderation and deployment security need design and review.
  Multi-host/high-availability operation is unsupported. Do not expose the local services publicly.
- The generated Hoi An-inspired image is fictional, not a photograph; see its
  [notice](../apps/web/public/hoi-an-generated.NOTICE.md). Review distribution rights before public use.
- Cursor updates are best effort and expire; reconnects and preview acknowledgement can be visible.
  Invalid placements preserve the last accepted preview; unsupported legacy arbitrary-angle boards
  remain readable but cannot be manipulated. The [latency assessment](multiplayer-latency-assessment.md)
  is a limited loopback measurement, not a general latency or frame-time benchmark.
- The [product plan](PLAN.md) is an earlier design proposal, not an implementation or wire contract.
  It retains decisions and future work; use the README and component docs for current behavior. The
  plan's dashboard, archive, identity and scaling proposals should not be assumed to be implemented.
- [Contracts](../packages/contracts/README.md), [generator](../packages/generator/README.md),
  [rules](../packages/rules/README.md), and [web game](../apps/web/src/game/README.md) document their
  respective interfaces. Preserve design-review material and unmerged branches when organizing docs;
  a merged baseline does not establish that all branch work landed.
