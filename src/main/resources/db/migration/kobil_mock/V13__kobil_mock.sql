-- Schema des Moduls `kobil_mock` - das simulierte Fremdsystem, nicht unser Bestand
-- (docs/07-betrieb.md Abschnitt 3: keine Aufbewahrungsfrist durch uns).

CREATE SCHEMA IF NOT EXISTS kobil_mock;

CREATE TABLE kobil_mock.ssms_user (
    user_id         VARCHAR(128) PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    -- Wie der aufrufende Dienst diesen Nutzer benennt - fuer KOBIL ein undurchsichtiger Wert.
    subject_ref     VARCHAR(255),
    pin             VARCHAR(32),
    activation_code VARCHAR(128),
    -- Entsteht erst bei der Aktivierung durch das Geraet.
    device_id       VARCHAR(255),
    -- Demo-Schalter: was die Sensoren dieses "Geraets" melden sollen, kommasepariert.
    risk_signals    VARCHAR(255) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Eine Assertion je erfolgreichem SDK-Login. Der Client bekommt nur den OTP als Referenz;
-- der Inhalt verlaesst KOBIL ausschliesslich ueber die serverseitige Einloesung.
CREATE TABLE kobil_mock.ssms_assertion (
    otp          VARCHAR(32) PRIMARY KEY,
    user_id      VARCHAR(128) NOT NULL,
    device_id    VARCHAR(255) NOT NULL,
    risk_signals VARCHAR(255) NOT NULL,
    -- Nicht NULL = verbraucht. Eine Assertion ist genau einmal einloesbar.
    redeemed_at  TIMESTAMP WITH TIME ZONE,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_kobil_mock_assertion_created_at ON kobil_mock.ssms_assertion (created_at);
