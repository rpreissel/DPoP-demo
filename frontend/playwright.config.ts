import { defineConfig } from '@playwright/test'
import { fileURLToPath } from 'url'
import { dirname } from 'path'

const __dirname = dirname(fileURLToPath(import.meta.url))

/**
 * One real end-to-end suite against the actual Spring Boot backend + built frontend (no mocks) -
 * the one thing App.test.tsx's mocked-api component tests structurally can't cover: real DPoP
 * WebCrypto proof generation in a real browser, real HTTP round trips, real server-side state.
 * Keep this suite small - anything expressible with mocked api.ts belongs in *.test.tsx instead.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  fullyParallel: false,
  workers: 1,
  use: {
    baseURL: 'http://localhost:8080',
    trace: 'retain-on-failure',
  },
  webServer: {
    // In-memory DB instead of the file-based dev one (application.yml): the seed data
    // (V2__testdata.sql) only has 3 persons, and a KVNR already provisioned by an earlier run
    // resolves to LOGIN/AUTH instead of the REGISTRATION/ENROLL path this suite exercises - a
    // fresh in-memory DB per server start guarantees that without touching disk state at all.
    command: './gradlew bootRun',
    cwd: dirname(__dirname),
    url: 'http://localhost:8080',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    env: {
      SPRING_DATASOURCE_URL: 'jdbc:h2:mem:e2e;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE',
    },
  },
})
