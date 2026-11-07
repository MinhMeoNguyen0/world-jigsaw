import { defineConfig, devices } from '@playwright/test'

const port = '4173'
const isCI = Boolean(process.env.CI)

// Smoke tests run against the production build served by `vite preview`.
// Run `pnpm build` first (the root `pnpm test:e2e` script expects a fresh build).
export default defineConfig({
  testDir: './e2e',
  forbidOnly: isCI,
  retries: isCI ? 2 : 0,
  reporter: isCI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    trace: 'on-first-retry',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: `pnpm exec vite preview --host 127.0.0.1 --port ${port} --strictPort`,
    url: `http://127.0.0.1:${port}`,
    reuseExistingServer: !isCI,
  },
})
