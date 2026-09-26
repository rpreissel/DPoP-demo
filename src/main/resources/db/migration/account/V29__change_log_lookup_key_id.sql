-- Welches Geheimnis einen Suchschluessel berechnet hat (zweite Bewertung F-10): der Weg, das
-- Geheimnis zu wechseln, ohne aeltere Eintraege unauffindbar zu machen (PersonLookupKey).
-- Alle bisherigen Schluessel stammen vom ersten Geheimnis, Id '1'.
ALTER TABLE account.change_log ADD COLUMN lookup_key_id VARCHAR(16);
UPDATE account.change_log SET lookup_key_id = '1' WHERE lookup_key IS NOT NULL;
