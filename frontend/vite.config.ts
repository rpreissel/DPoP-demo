import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { resolve } from 'path'

// https://vite.dev/config/
const frontendDir = import.meta.dirname

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: resolve(frontendDir, '../src/main/resources/static'),
    emptyOutDir: true,
    rollupOptions: {
      // Six separate apps (docs/10-frontend.md #0: Willkommen/App-Kanal/Web-Kanal/Admin/
      // Personenregister/Nect-Sprungseite), sharing
      // components/tools/api.ts as plain imports but each with its own HTML entry/React root - so
      // navigation between them is real browser navigation, not client-side routing.
      input: {
        welcome: resolve(frontendDir, 'index.html'),
        app: resolve(frontendDir, 'app/index.html'),
        web: resolve(frontendDir, 'web/index.html'),
        admin: resolve(frontendDir, 'admin/index.html'),
        ext: resolve(frontendDir, 'ext/index.html'),
        nect: resolve(frontendDir, 'nect/index.html'),
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
      '/mock-stammdaten': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/mock-nect': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
