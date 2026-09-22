-- Das Schluesselpaar, mit dem sich dieser Orchestrator bei Keycloak als Client ausweist
-- (private_key_jwt statt client_secret, docs/12-entscheidungen.md ADR-25).
--
-- Anders als orchestrator.keycloak_keypair gehoert dieses Paar keinem Account, sondern dem Knoten
-- selbst - deshalb eine eigene Tabelle statt eines Sonderfalls dort. Es liegt in der DB und nicht
-- im Prozessspeicher, damit mehrere Orchestrator-Instanzen dieselbe Client-Identitaet tragen: sonst
-- muesste Keycloak fuer jede Instanz einen anderen Schluessel kennen, und ein Neustart entzoege
-- allen anderen Knoten nichts, waehrend dieser hier unbemerkt mit einem fremden kid signiert.
--
-- purpose ist der Primaerschluessel und nicht eine generierte Id: pro Zweck genau eine Zeile, und
-- zwei gleichzeitig startende Instanzen koennen dadurch nicht zwei Paare nebeneinander anlegen -
-- die zweite laeuft in den Primaerschluessel und liest stattdessen das vorhandene.
--
-- Demo-Rahmen wie bei orchestrator.keycloak_keypair: private_key_jwk liegt im Klartext (ADR-22),
-- wird von keiner API je herausgegeben und nur zum Signieren ausgehender Assertions gelesen.
CREATE TABLE orchestrator.node_signing_key (
    purpose         VARCHAR(64)   PRIMARY KEY,
    public_key_jwk  VARCHAR(2000) NOT NULL,
    private_key_jwk VARCHAR(2000) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);
