-- Namensvetter im Register finden (ADR-18, Nachtrag 2026-09-26): Personen gleichen Geburtsdatums,
-- deren Namen der Adapter dann in MRZ-Form vergleicht. Ohne Index waere das ein Durchlauf ueber alle
-- Personen je Zuordnung.
CREATE INDEX ix_person_geburtsdatum ON ext_personenverzeichnis.person (geburtsdatum);
