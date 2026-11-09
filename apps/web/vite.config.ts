import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// Local development only. Preserve the browser Host and Origin so the services can
// enforce their own loopback policies. The chat stream must not be buffered.
export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    proxy: {
      '/api/v1/chat': { target: 'http://127.0.0.1:8081' },
      '/api/v1': { target: 'http://127.0.0.1:8080', ws: true },
    },
  },
  test: {
    include: ['src/**/*.test.{ts,tsx}'],
  },
})
