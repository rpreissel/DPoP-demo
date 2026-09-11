import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { resolve } from 'path'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: resolve(__dirname, '../src/main/resources/static'),
    emptyOutDir: true,
    rollupOptions: {
      // Three separate apps (docs/10-frontend.md #1: Willkommen/App-Kanal/Web-Kanal), sharing
      // components/tools/api.ts as plain imports but each with its own HTML entry/React root - so
      // navigation between them is real browser navigation, not client-side routing.
      input: {
        welcome: resolve(__dirname, 'index.html'),
        app: resolve(__dirname, 'app/index.html'),
        web: resolve(__dirname, 'web/index.html'),
      },
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/orchestrator': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/mock-keycloak': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
