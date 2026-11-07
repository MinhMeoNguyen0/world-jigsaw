# World Jigsaw

A browser-based, collaborative daily jigsaw puzzle. The product and engineering plan is in
[`docs/PLAN.md`](docs/PLAN.md).

## Repository layout

| Path               | Contents                                          |
| ------------------ | ------------------------------------------------- |
| `apps/web`         | Web client: Vite, React, TypeScript, React Router |
| `services/backend` | Java Spring Boot backend                          |
| `docs`             | Product and engineering plan                      |
| `.github`          | CI workflows and Dependabot configuration         |

## Local setup

Requirements:

- **Node.js 26.13.0** (the Active LTS release, pinned in `.nvmrc`). With nvm: `nvm install && nvm use`.
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
| `pnpm test`         | Unit tests (Vitest)                                     |
| `pnpm build`        | Production build of the web app                         |
| `pnpm test:e2e`     | Builds the web app, then runs the Playwright smoke test |
| `pnpm check`        | All of the above, in CI order                           |

A separate workflow (`.github/workflows/secret-scan.yml`) scans for leaked secrets with
[Gitleaks](https://github.com/gitleaks/gitleaks). Pull requests and pushes to `main` scan their new
commits; the weekly run (or a manual run) scans the full git history.

## Interface text

User-facing text lives in `apps/web/src/i18n/locales/en.json`. Components render it with `t()`
from `apps/web/src/i18n/translate.ts`. ESLint rejects literal text written directly in JSX:

- text between tags, such as `<p>Play now</p>`;
- a string or template literal written as a child, such as `<p>{'Play now'}</p>`;
- a string or template literal given to a text attribute (`alt`, `title`, `placeholder`, `label`,
  `aria-label`, `aria-description`, `aria-placeholder`, `aria-roledescription`,
  `aria-valuetext`), such as `alt="Map"` or `aria-label={'Close'}`.

Whitespace-only string literals such as `{' '}` and `alt=""` are allowed. Text assembled in other
expressions (variables, conditionals, concatenation) is not detected, so keep it in the catalog too.

## Backend local setup

The backend is a Java 21 Spring Boot service in `services/backend`, built with the Gradle wrapper. You need JDK 21 or newer to run Gradle (the formatter, google-java-format 2.0.0, requires it) and Docker.

1. Start PostgreSQL from the repository root:

   ```sh
   docker compose up -d postgres
   ```

2. Run the backend (it applies Flyway migrations on startup):

   ```sh
   cd services/backend
   ./gradlew bootRun
   ```

   Check it is up: `curl http://localhost:8080/actuator/health` reports `"status":"UP"`.

3. Run the build: formatting check and tests. The tests start their own PostgreSQL container with Testcontainers, so Docker must be running, but the Compose database is not needed:

   ```sh
   cd services/backend
   ./gradlew build
   ```

   Fix formatting with `./gradlew spotlessApply`.
