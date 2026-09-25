import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { keycloakify } from 'keycloakify/vite-plugin'
import { fileURLToPath } from 'node:url'
import { themeProperties } from './scripts/texts-per-page.mjs'

/** The FreeMarker theme next door: source of the shared tokens.css and the messages bundles. */
const freemarkerTheme = fileURLToPath(new URL('../keycloak-extension/src/main/resources/theme/orchestrator/login', import.meta.url))

const THEME_NAME = 'orchestrator-keycloakify'

export default defineConfig({
  plugins: [
    react(),
    keycloakify({
      themeName: THEME_NAME,
      accountThemeImplementation: 'none',
      // Keycloak 26 only - one jar, named for the image.
      keycloakVersionTargets: { '22-to-25': false, 'all-other-versions': `${THEME_NAME}-theme.jar` },
      // Parent is the FreeMarker theme: every page without a component here (for now most tool
      // pages) falls back to its template, and its messages are inherited - so the extension's
      // KcTexts finds its texts with either theme active (docs/ideen/keycloakify-statt-freemarker.md).
      // orchestratorTexts.<pageId> names the texts each React page uses; the extension sends a
      // page only those (kcContext.texts).
      extraThemeProperties: ['parent=orchestrator', ...themeProperties(fileURLToPath(new URL('./src', import.meta.url)))],
    }),
  ],
  server: {
    fs: { allow: ['.', freemarkerTheme] },
  },
})

