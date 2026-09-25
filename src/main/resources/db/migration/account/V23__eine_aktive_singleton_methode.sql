-- I-13 (docs/invarianten.md): je Konto hoechstens eine aktive Instanz einer Singleton-Methode.
-- Mehrere Instanzen erlauben nur die Verfahren, deren Deskriptor allowsMultipleInstances setzt -
-- heute 'device' und 'kobil'. Die Liste steht hier ein zweites Mal; SingletonMethodConstraintTest
-- prueft, dass sie zu den Deskriptoren passt.
--
-- Wie bei V22: eine berechnete Spalte statt eines partiellen Index, den H2 nicht kennt.
UPDATE account.auth_method m SET active = FALSE, deactivated_at = CURRENT_TIMESTAMP
WHERE m.active AND m.method NOT IN ('device', 'kobil') AND EXISTS (
    SELECT 1 FROM account.auth_method n
    WHERE n.account_id = m.account_id AND n.method = m.method AND n.active AND n.created_at > m.created_at
);
ALTER TABLE account.auth_method ADD COLUMN active_singleton_account_id BIGINT
    GENERATED ALWAYS AS (CASE WHEN active AND method NOT IN ('device', 'kobil') THEN account_id END);
CREATE UNIQUE INDEX ux_auth_method_active_singleton ON account.auth_method (active_singleton_account_id, method);
