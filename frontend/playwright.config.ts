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
// Deliberately NOT the dev port (8080, application.yml): this suite must own its server, and a
// dev instance left running there serves the file-based dev DB.
const E2E_PORT = 8091

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  fullyParallel: false,
  workers: 1,
  use: {
    baseURL: `http://localhost:${E2E_PORT}`,
    trace: 'retain-on-failure',
  },
  webServer: {
    // A fresh in-memory DB per run is a correctness requirement, not a convenience: the seed data
    // (V2__testdata.sql) has only 3 persons, and a KVNR already provisioned by an earlier run
    // resolves to LOGIN/AUTH instead of the REGISTRATION/ENROLL path this suite exercises.
    //
    // Hence reuseExistingServer: false and a port of our own. Reusing whatever already listens
    // would silently ignore the SPRING_DATASOURCE_URL below - the failure mode this replaces,
    // where the suite ran green in CI but hit the dev file DB locally and failed on the second run.
    command: `./gradlew bootRun --args='--server.port=${E2E_PORT}'`,
    cwd: dirname(__dirname),
    url: `http://localhost:${E2E_PORT}`,
    reuseExistingServer: false,
    timeout: 120_000,
    env: {
      SPRING_DATASOURCE_URL: `jdbc:h2:mem:e2e-${Date.now()};DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE`,
    },
  },
})
