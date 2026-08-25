import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// Kept separate from vite.config.ts on purpose: vitest bundles its own (older) vite version, and
// merging `test` into the main config's defineConfig() trips a type mismatch between the two
// Plugin types. Vitest resolves this file in preference to vite.config.ts automatically.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    exclude: ['**/node_modules/**', '**/e2e/**'],
  },
})
