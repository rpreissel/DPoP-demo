-- Spring Modulith Event Publication Registry: der transaktionale Outbox fuer Ereignisse, die ein
-- Modul nach dem Commit verarbeitet (docs/07-betrieb.md Abschnitt 3a).
--
-- Inhaltlich unveraendert uebernommen aus
-- spring-modulith-events-jdbc-2.1.1.jar:/org/springframework/modulith/events/jdbc/schemas/v2/schema-h2.sql
-- Modulith kann diese Tabelle beim Start selbst anlegen; in diesem Projekt legt Flyway jedes
-- Schema an (ADR-16), deshalb steht sie hier. Beim Anheben der Modulith-Version gegen die dann
-- mitgelieferte Datei vergleichen.
CREATE TABLE IF NOT EXISTS orchestrator.event_publication
(
  ID                     UUID NOT NULL,
  COMPLETION_DATE        TIMESTAMP(9) WITH TIME ZONE,
  EVENT_TYPE             VARCHAR(512) NOT NULL,
  LISTENER_ID            VARCHAR(512) NOT NULL,
  PUBLICATION_DATE       TIMESTAMP(9) WITH TIME ZONE NOT NULL,
  SERIALIZED_EVENT       VARCHAR(4000) NOT NULL,
  STATUS                 VARCHAR(20),
  COMPLETION_ATTEMPTS    INT,
  LAST_RESUBMISSION_DATE TIMESTAMP(9) WITH TIME ZONE,
  PRIMARY KEY (ID)
);
CREATE INDEX IF NOT EXISTS EVENT_PUBLICATION_BY_LISTENER_ID_AND_SERIALIZED_EVENT_IDX ON orchestrator.event_publication (LISTENER_ID, SERIALIZED_EVENT);
CREATE INDEX IF NOT EXISTS EVENT_PUBLICATION_BY_COMPLETION_DATE_IDX ON orchestrator.event_publication (COMPLETION_DATE);
