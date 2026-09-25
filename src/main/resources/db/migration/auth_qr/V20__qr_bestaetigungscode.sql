-- Code in Gegenrichtung statt Vergleichscode (Review 2026-09, M-2; docs/07-betrieb.md #5).
--
-- Bisher zeigten Browser und App denselben dreistelligen Vergleichscode, und die Freigabe in der App
-- meldete den Browser sofort an. Ein Angreifer, der dem Opfer seinen eigenen Pairing-Code per Link
-- schickte, schrieb den Vergleichscode einfach mit in die Nachricht. Jetzt entsteht erst bei der
-- Freigabe ein Bestaetigungscode, den nur die App anzeigt und den man in den Browser eintippt - in den
-- Browser des Angreifers kann das Opfer nicht tippen.
--
-- Gespeichert wird nur sein Hash; die App bekommt den Klartext genau einmal, in der Antwort auf die
-- Freigabe. confirmation_attempts begrenzt das Raten im Browser: nach drei Fehlversuchen ist die
-- Anfrage verbrannt.
ALTER TABLE auth_qr.login_request DROP COLUMN verification_code;
ALTER TABLE auth_qr.login_request ADD COLUMN confirmation_code_hash VARCHAR(64);
ALTER TABLE auth_qr.login_request ADD COLUMN confirmation_attempts INT DEFAULT 0 NOT NULL;
