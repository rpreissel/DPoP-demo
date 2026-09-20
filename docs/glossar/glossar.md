# Glossar (Quelle)

> Begriffsdefinitionen zu Authentifizierung, Identifizierung und Gerätebindung.
> Externes Referenzdokument, unverändert übernommen aus `glossar.mdx`. Der Abgleich mit
> diesem Projekt steht in [abgleich.md](abgleich.md).

---

## Authentifizierung

Das sichere Wiedererkennen eines Kommunikationspartners aus einer früheren Kommunikation.

**Beispiel:** Der Kommunikationspartner Server erkennt den Kommunikationspartner Client sicher wieder.

Je nach Kontext meint der Begriff entweder nur den Ablauf im Server, bei dem Authentisierungsmittel des Clients geprüft werden, oder umfasst auch die Authentisierung des Clients.

**Voraussetzung zur Durchführung:**

- In einer früheren Kommunikation zwischen denselben Kommunikationspartnern wurde ein Authentisierungsmittel des Clients erstellt und mit dem Server vereinbart.
- Alternativ ist auch eine Vereinbarung mit einem Dritten möglich, dem der Server vertraut.

## Server, Client und Person

Kommunikationspartner sind jeweils Server und Client.

Der Begriff Client und die den Client nutzende Person können hier teilweise synonym verstanden werden, solange für die jeweilige Begriffsdefinition aus Server-Sicht nicht zwischen Client und Nutzer unterschieden werden muss.

## Sichere Kommunikation

Ein sequentieller Austausch von Nachrichten zwischen zwei Kommunikationspartnern, hier Client und Server, in einem sicheren Kommunikationskanal.

## Sicherer Kommunikationskanal

Eine zeitlich begrenzte verschlüsselte Verbindung zwischen zwei Kommunikationspartnern, innerhalb derer 1-n Nachrichten ausgetauscht werden.

**Beispiel:** `https`

## Nachricht

1 Request (= eine Nachricht), optional gefolgt von 1 Response (= eine weitere Nachricht).

## Kommunikationspartner

Ein kommunikationsfähiges Gerät.

Hier ist ein Kommunikationspartner entweder ein Client oder ein Server:

- Der Server repräsentiert eine Organisation.
- Der Client repräsentiert das Gerät einer natürlichen Person.
- Die natürliche Person besitzt den Client, hier insbesondere das Smartphone der App.

## Authentisierungsmittel

Etwas, mittels dessen sich ein Client gegenüber einem Server authentisieren kann.

Ein Authentisierungsmittel besteht aus 1-n zusammengehörenden Authentisierungsfaktoren.

## Authentisieren

Vom Client die Übermittlung eines Nachweises über das Innehaben eines Authentisierungsmittels, anhand dessen der Server den Client als Kommunikationspartner wiedererkennen kann.

Der Nachweis erfolgt innerhalb einer Kommunikation mittels 1-n Nachrichten.

## Authentisierungsfaktor (kurz auch: Faktor)

Ein Teil des Authentisierungsmittels, falls das Authentisierungsmittel aus `n` Faktoren besteht (`n > 1`). Die Teile sind miteinander kryptographisch verknüpft.

Ein Faktor hat einen Faktortyp, zum Beispiel Wissen, Besitz oder Biometrie.

Es gibt auch Authentisierungsmittel, die aus nur 1 Faktor bestehen (`n = 1`), zum Beispiel das klassische Passwort.

## Faktortyp Wissen

Ein Geheimnis, das sich eine Person gemerkt hat.

**Beispiele:** PIN, Passwort, Passphrase.

## Faktortyp Besitz

Ein physischer Gegenstand im Besitz einer Person, auch Gerät genannt.

Das Gerät enthält Merkmale oder Daten, die es einmalig machen. Diese können nicht in ein anderes Gerät übertragen oder kopiert werden.

**Beispiel:** Ein Endgerät wie Smartphone oder Hardwaretoken, genauer ein sicherer Speicher wie Secure Element oder Trusted Platform Module, für einen asymmetrischen privaten Schlüsselwert.

Der private Schlüsselwert ist einmalig. Dadurch kann das Gerät eindeutig wiedererkannt werden. Dieses Wiedererkennen ist nur möglich, solange der Schlüssel existiert. Nach einem Löschen der Gerätebindung inklusive privater Schlüssel ist das Gerät nicht mehr wiedererkennbar, auch nicht nach einer neu erstellten Gerätebindung für dasselbe Gerät.

Für den Faktortyp Besitz ist nicht erforderlich, dass ein Gerät über die gesamte Lebensdauer hinweg wiedererkannt werden kann. Erforderlich ist, dass ein konkretes Gerät während der Lebensdauer einer Gerätebindung eindeutig wiedererkannt werden kann.

Auch wenn auf einem Smartphone eine App einen app-spezifischen privaten Schlüssel im sicheren Speicher erstellt, kann darüber nicht nur diese konkrete App wiedererkannt werden, sondern tatsächlich auch das Smartphone selbst. Der Grund: Der private Schlüssel kann nicht aus dem sicheren Speicher des Smartphones in ein anderes Gerät kopiert oder übertragen werden.

## 2-Faktor-Authentisierungsmittel / n-Faktor-Authentisierungsmittel

Ein Authentisierungsmittel, das aus `n` Faktoren unterschiedlicher Faktortypen besteht, die miteinander kombiniert sind, zum Beispiel mit `n = 2`.

**Beispiel 1:** Ein Faktor vom Typ Besitz kombiniert mit einem Faktor vom Typ Wissen. Als Voraussetzung zur Nutzung des Faktors Besitz authentifiziert das Gerät den Gerätenutzer mittels PIN.

**Beispiel 2:** Ein Endgerät wie Smartphone oder PC mit einem sicheren Speicher für einen asymmetrischen privaten Schlüsselwert, der erst verwendet werden kann, wenn er vom Gerätenutzer lokal auf dem Gerät mit einem Faktor vom Typ Wissen oder Biometrie freigeschaltet wird.

Der Faktor Wissen oder Biometrie wird dabei nicht an einen Server gesendet, sondern nur vom Gerät selbst geprüft. Das Gerät führt also eine 1-Faktor-Authentifizierung des Gerätenutzers durch als Voraussetzung zur Nutzung des privaten Schlüssels.

**Hinweis:** Zwei Faktoren eines Clients, die nicht miteinander verknüpft sind und unabhängig voneinander einzeln vom Server geprüft werden, gelten nicht als 2-Faktor-Authentisierungsmittel. In diesem Fall handelt es sich eher um zwei 1-Faktor-Authentisierungsmittel; deren Nutzung wird als mehrstufige Authentifizierung bezeichnet.

## 2-Faktor-Authentifizierung (kurz auch: 2FA) / n-Faktor-Authentifizierung (kurz auch: MFA)

Die Authentifizierung unter Verwendung eines 2-Faktor-Authentisierungsmittels.

Multi-Faktor-Authentifizierung (MFA) steht für eine Authentifizierung unter Verwendung eines Authentisierungsmittels, das aus `n` miteinander verknüpften Faktoren mit `n > 1` besteht. Der Begriff MFA wird gegebenenfalls auch alternativ zu 2FA verwendet, also dann mit `n = 2`.

Die Faktoren des Authentisierungsmittels sind miteinander verknüpft, siehe dazu die Begriffsdefinition von n-Faktor-Authentisierungsmittel.

Zur Durchführung einer 2FA oder MFA kann der Server unter anderem ein Challenge-Response-Verfahren verwenden.

## Gerätebindung

Die Erstellung eines Authentisierungsfaktors vom Typ Besitz.

Dabei wird ein konkreter Faktorwert für das Gerät erstellt, zum Beispiel ein asymmetrisches Schlüsselpaar.

**Hinweis:** Als Begriff wird bewusst "Geräte"-Bindung verwendet und nicht zum Beispiel bei Smartphones "App"-Bindung. Siehe dazu die Begriffsdefinition von "Faktor Besitz".

**Beispiel:** Ein Gerät, also der Client, erstellt in Abstimmung mit dem Server ein Authentisierungsmittel, das aus mindestens diesem Authentisierungsfaktor besteht.

Dabei muss der Server oder der Gerätenutzer dem Client, also dem Gerät, vertrauen, dass dieses die Anforderungen an das Authentisierungsmittel erfüllt. Wer wem wie weit vertrauen oder dies aktiv prüfen muss, hängt von den Sicherheitsanforderungen des Verfahrens, dem Einsatzzweck und gegebenenfalls auch vom Gerätemodell ab.

Grundsätzlich gilt: Je angriffssicherer der sichere Speicher und die Nutzer-Authentifizierung des Geräts zur Nutzung der Inhalte dieses Speichers sind, desto leichter kann dem Gerät vertraut werden oder desto leichter kann dieses geprüft werden.

## Gerätebindung für MFA

Die Erstellung eines Authentisierungsfaktors vom Typ Besitz im Rahmen der Erstellung eines n-Faktor-Authentisierungsmittels, zum Beispiel mit `n = 2` für 2FA.

Siehe hierzu auch die Begriffsdefinition Gerätebindung.

**Beispiel:** Ein Gerät, also der Client, erstellt in Abstimmung mit dem Server ein 2-Faktor-Authentisierungsmittel. Dabei wird ein konkreter Faktorwert für das Gerät erstellt, zum Beispiel ein asymmetrisches Schlüsselpaar, und zusätzlich ein konkreter Faktorwert für den Nutzer, zum Beispiel eine PIN.

Da bei MFA die Faktoren miteinander verknüpft sein müssen, authentifiziert das Gerät den Gerätenutzer mittels PIN als Voraussetzung zur Nutzung des Faktors Besitz.

**Anmerkung zur praktischen Umsetzung:**

Ein theoretischer Idealfall wäre hier eine nach höchsten Sicherheitsanforderungen zertifizierte Smartcard. Diese würde zusätzlich zum sicheren Speicher auch eine vertrauenswürdige Eingabemöglichkeit, zum Beispiel ein PIN-Pad, und eine vertrauenswürdige Ausgabemöglichkeit, zum Beispiel ein Display, umfassen.

Prinzipiell könnte ein Smartphone dies aufgrund seines Aufbaus ebenfalls leisten. In der Praxis bestehen derzeit aber vor allem diese Schwierigkeiten:

- Sicherheitsanforderungen eines Smartphones an ein 2-Faktor-Authentisierungsmittel sind nicht vollständig erfüllt, zum Beispiel wegen Sicherheitslücken.
- Sicherheitsfeatures des Smartphones sind nicht einfach vertrauenswürdig nachweisbar, zum Beispiel wegen fehlender Zertifizierung.
- Vorhandene Sicherheitsfeatures sind nicht immer über öffentliche Schnittstellen nutzbar.
- Die Umsetzung ist zwischen unterschiedlichen Smartphone-Modellen und Herstellern nicht standardisiert.

Dadurch wird die Umsetzung umso aufwendiger, je größer die Nutzerbasis ist und je mehr unterschiedliche Smartphones bereits im Einsatz sind.

## Sicherer Speicher

Ein abgesicherter Speicher für Daten, zum Beispiel Schlüssel.

Eine reine Speichermöglichkeit ist nicht ausreichend. Der sichere Speicher bietet insbesondere für gespeicherte geheime Schlüsselwerte kryptographische Funktionen an, bei denen lediglich das Funktionsergebnis den Speicher nach außen verlässt. Insbesondere verlässt der geheime Schlüsselwert den sicheren Speicher nicht.

Damit ist die Nutzung dieses Speichers für asymmetrische Kryptographie möglich.

**Beispiele:** Secure Element (SE), Trusted Platform Module (TPM), Smartcard-Chip.

## Identifizierungsmittel

Ein Authentifizierungsmittel, das spezifisch für eine bestimmte Identität ausgestellt worden ist und mindestens 1 Attribut dieser Identität bescheinigt.

Ein Identifizierungsmittel ist immer auch ein Authentifizierungsmittel. Umgekehrt ist ein Authentifizierungsmittel nicht immer auch ein Identifizierungsmittel.

**Beispiel:** Die Ausstellung erfolgt für eine bestimmte Person inklusive eines Attributs dieser Person. Das Authentifizierungsmittel ist im alleinigen Besitz genau dieser zugehörigen Person, zum Beispiel eID-PIN als Wissen oder Ausweis-Foto als Biometrie.

Ein Attribut gilt für einen Server als bescheinigt, wenn seine Zugehörigkeit zu dieser Person vom Server geprüft werden kann. Das Attribut muss also mit einer Zertifizierung versehen sein, der der Server vertraut.

Die Zertifizierung muss dazu neben den bescheinigten Attributen zusätzlich einen Bezeichner besitzen, der für diese Person eindeutig ist, in der Regel sogar eindeutig für dieses konkrete Identifizierungsmittel, zum Beispiel eine Ausweisnummer. Dieser eindeutige Bezeichner muss dem Server bereits im Rahmen der Authentifizierung sicher mitgeteilt worden sein, zum Beispiel als Attribut im Public-Key-Zertifikat.

**Beispiele:**

- Personalausweis mit Attributen wie Name, Geburtsdatum und Anschrift.
- Versicherungs-Smartcard mit Attributen wie Versichertennummer.

Alternativ zur Zertifizierungsprüfung sind auch spezifische nicht-kryptographische Prüfverfahren möglich. Diese sind jedoch weniger sicher als rein kryptographische Verfahren, zum Beispiel die Übermittlung eines Einmalcodes.

**Beispiele:**

- E-Mail-Konto mit dem Attribut E-Mail-Adresse. Der Server geht bei der Identifizierung implizit davon aus, dass die Person mit einem ihm unbekannten Authentifizierungsverfahren des E-Mail-Providers authentifiziert wurde, zum Beispiel Passwort bei POP3 oder SMTP.
- SIM-Karte mit dem Attribut Telefonnummer. Der Server geht bei der Identifizierung implizit davon aus, dass der Besitz der Telefonnummer durch eine SIM-Karte mit PIN geschuetzt ist.

## Bescheinigtes Attribut (auch: bescheinigtes Identitätsmerkmal)

Ein von einem vertrauten Server, zum Beispiel einer Meldebehörde, bescheinigtes Merkmal einer Identität, zum Beispiel einer Person.

Die Bescheinigung erfolgt üblicherweise mittels Zertifizierung oder eines kryptographisch gleichwertig sicheren Verfahrens. Dadurch können Dritte, zum Beispiel ein Server, der eine Person identifizieren will, der Bescheinigung vertrauen und das Attribut sicher eindeutig dieser Person zuordnen.

**Beispiel:** Ein Zertifikat eines vertrauten Zertifikatsausstellers, das das Attribut enthält und zusätzlich einen Bezeichner, mittels dessen ein Server dieses Attribut eindeutig einer einzelnen Person zuordnen kann.

## Unbescheinigtes Attribut (auch unbescheinigtes Identitätsmerkmal)

Im Unterschied zu einem bescheinigten Attribut ist ein unbescheinigtes Attribut ein vom Client ohne Bescheinigung dem Server angegebenes, also behauptetes, Identitätsmerkmal.

Typischerweise sind das alle Angaben, die der Client dem Server übermittelt, nachdem der Server den Client aus seiner Sicht ausreichend identifiziert hat.

**Beispiel:** Die bereits vorher vom Server gesammelten bescheinigten Identitätsmerkmale reichen für die eindeutige Zuordnung eines im Hintergrundsystem bereits vorhandenen Stammdatensatzes aus.

Unbescheinigte Attribute dürfen niemals als relevante Merkmale für eine solche Zuordnung bereits vorhandener Stamm- oder personenbezogener Datensätze verwendet werden. Sie dürfen jedoch jederzeit vom Server empfangen und der Identität zugeordnet werden. Dabei muss erkennbar bleiben, ob ein Attribut bescheinigt oder unbescheinigt ist.

## Identifizierung

Im Server die Anreicherung der Informationen über einen bereits wiedererkannten Client, zum Beispiel über die diesen Client exklusiv nutzende wiedererkannte Person, mit einem oder mehreren bescheinigten Attributen.

Eine Identifizierung muss nicht zwingend anhand eines Identifizierungsmittels erfolgen. Es ist auch möglich, dass der Server den Client zunächst nur mittels eines Authentifizierungsmittels authentifiziert. Danach kann der Server in einem zweiten, davon unabhängigen Schritt von irgendwoher ein oder mehrere bescheinigte Attribute erhalten, zum Beispiel ein Zertifikat. Damit erhält er auch einen eindeutigen Bezeichner des Clients, anhand dessen er die Attribute noch während der laufenden Kommunikation eindeutig dem bereits wiedererkannten Client zuordnen kann.

**Beispiel aus der analogen Welt:** Das Bahnticket bescheinigt das Attribut "Fahrkarte vorhanden" und enthält als eindeutigen Personenbezeichner den Namen. Der zusätzlich vorzuzeigende Personalausweis verknüpft dieses Attribut für den Fahrkartenkontrolleur eindeutig mit der vor ihm sitzenden Person, da nur auf diesem Ausweis ein Personenfoto als Authentisierungsfaktor vorhanden ist.

Der Personalausweis kann hier entweder als reines Authentisierungsmittel interpretiert werden oder selbst als Identifizierungsmittel. Die Prüfung des Bahntickets wäre dann eine zweite Identifizierung, bei der die festgestellte Identität um das weitere bescheinigte Identitätsmerkmal "Fahrkarte vorhanden" angereichert wird.

## Identität (ID)

Eine Sammlung von Attributen, die einem Client beziehungsweise einer Person zugeordnet sind.

## ID-Server/ID-System

Ein Server beziehungsweise System, das Identitäten kennt und diese für andere Server sicher nutzbar macht.

Dazu kann es:

- Identitäten verwalten, also ergänzen, ändern und entfernen.
- Attribute zu Identitäten verwalten, also ebenfalls ergänzen, ändern und entfernen.

Es macht Identitäten für andere Server nutzbar, indem es diesen Informationen über einzelne Identitäten zur Verfügung stellt, konkret Attribute und insbesondere diese zusammen mit Statusinformationen über erfolgte Authentifizierungen, zum Beispiel in Form von Token.

Bei allen Verwaltungsoperationen berücksichtigt es Sicherheitsanforderungen wie erforderliche Identifizierungen oder Authentifizierungen.
