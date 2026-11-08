# World Jigsaw

A browser-based, collaborative daily jigsaw puzzle. The product and engineering plan is in
[`docs/PLAN.md`](docs/PLAN.md).

## Repository layout

| Path                 | Contents                                           |
| -------------------- | -------------------------------------------------- |
| `apps/web`           | Web client: Vite, React, TypeScript, React Router  |
| `services/backend`   | Java Spring Boot backend                           |
| `services/chat`      | Independent local ephemeral chat relay             |
| `packages/contracts` | Versioned shared schemas, fixtures and types       |
| `packages/generator` | Deterministic puzzle generator and review fixtures |
| `packages/rules`     | Local puzzle rules                                 |
| `docs`               | Product and engineering plan                       |
| `.github`            | CI workflows and Dependabot configuration          |

The [puzzle generator](packages/generator/README.md) produces standalone definitions and starting snapshots.

The [local rules package](packages/rules/README.md) computes draft puzzle interactions without saving or transmitting them.

The [shared v1 contract](packages/contracts/README.md) describes the integration baseline; the schemas do not implement a running multiplayer service.

## Local setup

Requirements:

- **Node.js 26.13.0** (pinned in `.nvmrc`). With nvm: `nvm install && nvm use`.
- **pnpm 12.18.0** (pinned in `package.json` `packageManager`). Install it with
  `npm install --global pnpm@12.18.0`, or run `corepack enable` if your Node.js ships Corepack.

Then, from the repository root:

```sh
pnpm install
pnpm dev        # start the web app at http://localhost:5173
```

To run the Playwright smoke test you also need its browser, once per machine:

```sh
pnpm --filter @world-jigsaw/web exec playwright install --with-deps chromium
```

## Checks

Run these from the repository root. CI (`.github/workflows/web.yml`) runs the same checks on every
pull request and on pushes to `main`.

| Command             | What it does                                            |
| ------------------- | ------------------------------------------------------- |
| `pnpm typecheck`    | TypeScript type check (strict mode)                     |
| `pnpm lint`         | ESLint                                                  |
| `pnpm format:check` | Prettier check (`pnpm format` rewrites files)           |
| `pnpm test`         | Shared contract, generator, rules and web unit tests    |
| `pnpm build`        | Production build of the web app                         |
| `pnpm test:e2e`     | Builds the web app, then runs the Playwright smoke test |
| `pnpm check`        | All of the above, in CI order                           |

A separate workflow (`.github/workflows/secret-scan.yml`) scans for leaked secrets with
[Gitleaks](https://github.com/gitleaks/gitleaks). Pull requests and pushes to `main` scan their new
commits; the weekly run (or a manual run) scans the full git history.

## Interface text

Keep user-facing component text in `apps/web/src/i18n/locales/en.json` and render it with `t()`
from `apps/web/src/i18n/translate.ts`. The initial HTML title is in `apps/web/index.html`.
ESLint rejects literal text written directly in JSX under `apps/web/src`:

- text between tags, such as `<p>Play now</p>`;
- a string or template literal written as a child, such as `<p>{'Play now'}</p>`;
- a string or template literal given to a text attribute (`alt`, `title`, `placeholder`, `label`,
  `aria-label`, `aria-description`, `aria-placeholder`, `aria-roledescription`,
  `aria-valuetext`), such as `alt="Map"` or `aria-label={'Close'}`.

Whitespace-only string literals such as `{' '}` and `alt=""` are allowed. Text assembled in other
expressions (variables, conditionals, concatenation) is not detected, so keep it in the catalog too.

## Backend local setup

The Java 21 Spring Boot backend runs independently of the web app and deployment tooling.
It binds to `127.0.0.1` by default. It has no public authentication or security review; changing
the bind address or placing it behind a proxy is not authorized as a public deployment.
You need JDK 21 or newer and a reachable PostgreSQL database. The database and its user must
already exist, and the user needs permission to run Flyway migrations. Connection settings
default to `jdbc:postgresql://localhost:5432/worldjigsaw`, user `worldjigsaw`, password
`worldjigsaw` (local development only). Override them for your database with
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD`.

For example, if Docker Compose is available, it can provide the local database from the
repository root. Docker and Compose are optional when using another PostgreSQL instance:

```sh
docker compose up -d postgres
```

Run the service from `services/backend`; Flyway applies migrations on startup:

```sh
./gradlew bootRun
```

Check it with `curl http://localhost:8080/actuator/health`; a healthy service reports
`"status":"UP"`. No frontend, gateway, dashboard, Kubernetes, or self-host platform is needed.

The private local game service provides `POST /api/v1/session` and an authenticated WebSocket at
`/api/v1/game/stream`. Session creation, mutations and the socket require an `Origin` matching the
service's literal `localhost` or `127.0.0.1` origin, including port. A session uses a host-only,
HttpOnly, SameSite=Strict cookie; its random secret is stored only as a hash. Sessions last 30 days.
This is local prototype identity, not public authentication. No proxy, cross-origin frontend, public
address or TLS termination has been reviewed. The service takes a PostgreSQL advisory lock to
reject a second local writer; it is not a distributed or high-availability deployment.

The socket accepts v1 `subscribe`, `preview.submit`, and `heartbeat` messages and fans out temporary
previews, clears, committed board events and UTC puzzle changes. `POST /api/v1/game/turns` opens a
turn; its `/confirm`, `/cancel`, and `/undo` routes settle or discard that turn. A confirmed batch,
its quota charge, board version, event and command result commit together. Cancel reveals the latest
saved board. Lost Confirm replies can be retried with the same command ID and payload or queried at
`GET /api/v1/game/commands/{commandId}`; an unknown old-puzzle command can supply `?puzzleId=`.
Receipts and identifier tombstones are retained indefinitely in this local prototype.

The [turn wire note](services/backend/TURN-WIRE.md) explains the local v1 join request and outgoing
sequence semantics. The [geometry note](services/backend/LOCAL-GEOMETRY.md) documents provisional
collision and snap rules. The current frontend is not integrated with these endpoints; its actual
mouse and touch controls still need the owning frontend worker's end-to-end validation. Chat is not
integrated. The separate [preview demo](services/backend/PREVIEW-INTEGRATION.md) is not the game
service.

Run formatting checks and tests from `services/backend`:

```sh
./gradlew build
```

The integration tests start their own PostgreSQL container with Testcontainers, so Docker must
be running for the tests; the Compose database is not needed. Fix formatting with
`./gradlew spotlessApply`.

### Game board import and reads

The backend owns the game database only. Chat must use a separate database and credentials; the
backend does not connect to it. A trusted operator can import a JSON document with `definition`
and `snapshot` fields, assigning its UTC day explicitly. For example, from `services/backend`:

```sh
./gradlew bootRun --args='--game-import-day=2026-11-07 --game-import-file=../../packages/generator/fixtures/review-60.json'
```

Import runs once during startup and fails startup on invalid input, a conflicting puzzle ID or UTC day, or an existing
board. A matching reserved puzzle identity can be used for its first import. Remove the import arguments on subsequent starts. The fixture is synthetic, not production
puzzle content. Import verifies the v1 JSON schemas, canonical SHA-256 checksum, definition and
snapshot identity, bounds, contour continuity, reciprocal edge geometry and complete unique piece
membership. It commits the definition and canonical board together. It does not verify all geometry,
collision or game rules; generated content needs further validation before production use.

`GET /api/v1/game/current` returns today's UTC definition and canonical snapshot, server time and
UTC close time. If today's puzzle has not been imported, it returns `PUZZLE_UNAVAILABLE` (503),
not a previous day's board. A valid local session receives its committed allowance and active turn;
without a session both are null. Reads use a repeatable-read database transaction.

Confirm locks the board before sampling the authoritative UTC clock. Admission strictly before
close permits the transaction to finish afterward; admission at or after close is rejected. An
unconfirmed preview is cleared at rollover without a charge. On restart, committed command results
remain queryable and unconfirmed drafts are discarded. Imported legacy arbitrary-angle boards remain
readable but interaction returns `UNSUPPORTED_LEGACY_ORIENTATION` in an `INVALID_ACTION` error;
their data is not rewritten.

## Chat local setup

See [`services/chat/README.md`](services/chat/README.md) for the independent development-only
relay, its API, and its delivery and security limitations.
