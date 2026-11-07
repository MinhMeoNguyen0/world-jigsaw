# World Jigsaw

A browser-based, collaborative daily jigsaw puzzle. The product and engineering plan is in
[`docs/PLAN.md`](docs/PLAN.md).

## Repository layout

| Path       | Contents                                          |
| ---------- | ------------------------------------------------- |
| `apps/web` | Web client: Vite, React, TypeScript, React Router |
| `docs`     | Product and engineering plan                      |
| `.github`  | CI workflows and Dependabot configuration         |

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

A separate workflow (`.github/workflows/secret-scan.yml`) scans the full git history for leaked
secrets with [Gitleaks](https://github.com/gitleaks/gitleaks) on every pull request, on pushes to
`main`, and weekly.

## Interface text

User-facing text lives in `apps/web/src/i18n/locales/en.json`. Components render it with `t()`
from `apps/web/src/i18n/translate.ts`; ESLint rejects literal text in JSX.
