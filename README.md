# World Jigsaw

A browser-based, collaborative daily jigsaw puzzle. The product and engineering plan is in
[`docs/PLAN.md`](docs/PLAN.md).

## Repository layout

| Path                 | Contents                                          |
| -------------------- | ------------------------------------------------- |
| `apps/web`           | Web client: Vite, React, TypeScript, React Router |
| `services/backend`   | Java Spring Boot backend                          |
| `packages/contracts` | Versioned shared schemas, fixtures and types      |
| `docs`               | Product and engineering plan                      |
| `.github`            | CI workflows and Dependabot configuration         |

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
| `pnpm test`         | Shared contract and web unit tests                      |
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

For the private preview transport demo and its limitations, see the
[backend integration note](services/backend/PREVIEW-INTEGRATION.md).

Run formatting checks and tests from `services/backend`:

```sh
./gradlew build
```

The integration tests start their own PostgreSQL container with Testcontainers, so Docker must
be running for the tests; the Compose database is not needed. Fix formatting with
`./gradlew spotlessApply`.
