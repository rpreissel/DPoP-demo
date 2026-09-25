-- Ein Schluesselpaar je Keycloak-Client statt eines gemeinsamen (Review 2026-09, S-3; ADR-25).
-- Die neuen Zeilen heissen 'keycloak-client-auth:<clientId>' und entstehen beim ersten Zugriff
-- (OrchestratorClientAssertionSigner). Das bisherige gemeinsame Paar wird hier geloescht: Es ist
-- ein privater Schluessel, den nichts mehr verwendet, und Keycloak kennt ihn nach dem Umstieg auf
-- die neuen jwks.url-Adressen nicht mehr.
DELETE FROM orchestrator.node_signing_key WHERE purpose = 'keycloak-client-auth';
