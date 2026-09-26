# ADR-24: Eine Methode hängt von einer anderen ab, indem sie deren Angabe verlangt

**Status**: umgesetzt.

**Entscheidung**: Abhängigkeiten zwischen Verfahren brauchen keine eigenen Begriffe. Ein Modul
schreibt beim Einrichten eine Angabe, ein anderes verlangt sie per `ClaimRequirement`. `requires`
entscheidet damit nicht nur darüber, ob ein Tool **angeboten** wird, sondern gilt **dauerhaft**: Fällt
die Angabe weg, fällt das Credential, das sie verlangte, mit. Das setzt sich über alles fort, was
seinerseits daran hängt, bis sich nichts mehr ändert (`JourneyActionExecutor.dependentsOfLostClaims`).

Heute genutzt wird das über die bestätigte Adresse: `enroll-password` und `enroll-email` verlangen
`ClaimRequirement(EMAIL, PROVEN)`. Eine zurückgenommene Adresse nimmt also Passwort und E-Mail-Login
mit. Damit eine bestätigte Adresse überhaupt verloren gehen kann, lässt sie sich **direkt**
zurücknehmen (`AccountService.retractAttribute`, `DELETE /channels/{id}/attributes/{attribute}`) –
einer der Auslöser in der Liste von [ADR-12](ADR-012-ein-widerruf-ist-eine-eigene-zeile-mit-eigenem.md).
Vorher erreichte sie kein Widerruf: `confirm-email` schreibt seine Angabe ohne `auth_method_id`, und
EMAIL gehört dem Konto selbst.

`enroll-password` behauptet zusätzlich `PASSWORD_EXISTS`, eine Angabe, die derzeit niemand verlangt.
Sie bleibt deklariert, weil sich damit eine Abhängigkeit vom Passwort ausdrücken ließe, ohne beim
nächsten Bedarf einen zweiten Mechanismus zu bauen.

Die Vorhersage, was ein Widerruf mitnimmt, muss genau mit dem Schreiben übereinstimmen: Welche Typen
eine widerrufene Instanz mitnimmt, ermittelt `AccountService.claimedTypesOf` mit derselben Abfrage und
demselben `MethodModule`-Filter wie der Widerruf selbst.

**Erwogene Alternative**: Eine eigene Descriptor-Eigenschaft `dependsOnMethods: Set<String>`, die
Methodennamen nennt. Zuerst so gebaut und wieder zurückgenommen: Sie hätte neben dem Claim-Modell eine
zweite Art eingeführt, Abhängigkeiten auszudrücken, und die Kette über die Adresse gar nicht erfasst,
weil dort kein Verfahren beteiligt ist.

**Begründung**: Das Claim-Log verwaltet ohnehin genau die Lebensdauer, um die es geht. Eine Abhängigkeit
als verlangte Angabe auszudrücken, braucht deshalb keinen neuen Mechanismus und wirkt auch dort, wo
kein Verfahren, sondern ein Attribut wegfällt.

**Folgen und Kosten**:
- `requires` bedeutet mehr als früher, und jede Anforderung hat diese Bedeutung. Für `EMAIL` ist das
  unkritisch, weil die Adresse nur über den ausdrücklichen Endpunkt verloren gehen kann. Das Ersetzen
  eines Ankers behauptet im selben Schritt den neuen Wert und löst deshalb nichts aus. Die Anforderung
  von `ident-kvnr` (`FAMILY_NAME`/`GIVEN_NAMES`/`BIRTH_DATE`, [ADR-18](ADR-018-bestaetigen-und-zuordnen-sind-zwei-akte.md))
  betrifft nur das Angebot, kein Credential.
- Ein Attribut zurückzunehmen hat weite Folgen. Das ist gewollt und wird vorher geprüft: Die Prüfung
  des Mindestniveaus rechnet mit allem, was mit entfällt, und eine Ablehnung nennt es beim Namen.
- Mit `PASSWORD_EXISTS` trägt `AttributeType` eine Aussage, die nichts über die Person sagt, sondern
  über die Credentials des Kontos.

**Geschichte**: `enroll-kobil` verlangte zunächst `PASSWORD_EXISTS`; das Kontopasswort war damit Pflicht
für eine KOBIL-Bindung. Das war fachlich falsch: Es sperrte KOBIL aus jeder Registrierung aus, die noch
kein Passwort hatte. Der Mechanismus blieb, die Kopplung fiel; an ihre Stelle trat, was
[ADR-21](ADR-021-der-kobil-pin-liegt-im-backend-und-das.md) beschreibt. Seit ADR-17 verlangt auch
`enroll-email` die bestätigte Adresse.
