# Handover & Debugging-Dokumentation: ChurchTools Heating Binding

**Datum:** 01. November 2026  
**Projekt:** openHAB Binding `churchtoolsheating` (`org.openhab.binding.churchtoolsheating`)  
**Status:** Fehler behoben & neu kompiliert (`5.2.1-SNAPSHOT`)

---

## 1. Problemstellung (Der Vorfall am 01.11.2026)

* **Szenario:** Ein Raum war in ChurchTools für Sonntag ab **09:00 Uhr** gebucht mit einer Soll-Temperatur von **21 °C**.
* **Bedingung nachts um 04:00 Uhr:** Ist-Temperatur lag bei **15 °C** ($\Delta T = 6 ^\circ\text{C}$).
* **Eingestellte Kennlinie:** Bei mehr als 4 °C Differenz sollte **6 Stunden** vorgeheizt werden ($\Delta T4 = 4.0 \rightarrow \text{Vorlauf}4 = 6.0\text{ h}$).
* **Erwartetes Verhalten:** Heizbeginn um **03:00 Uhr** morgens ($09:00 - 6\text{ h}$).
* **Tatsächliches Verhalten:** Die Heizung schaltete erst um **07:00 Uhr** morgens ein (exakt 2 Stunden Vorlauf).

---

## 2. Ursachenanalyse (Root Cause Analysis)

### Ursache A: Ist-Temperatur wurde ignoriert (Defaultwert 20 °C)
* **Code-Stelle:** `RoomHeaterHandler.java` in `handleCommand(...)`
* **Problem:** `CHANNEL_CURRENT_TEMPERATURE` und `CHANNEL_TARGET_TEMPERATURE` akzeptierten ausschließlich `state instanceof QuantityType`.
* Wenn openHAB, das KNX-Binding oder eine Rule die Ist-Temperatur als `DecimalType` (z.B. `15.0`) oder `StringType` gesendet hat, wurde der Wert stillschweigend verworfen.
* `currentTemp` verblieb auf dem Defaultwert `20.0 °C`.
* **Folge:** Das Binding rechnete intern mit:
  $$\Delta T = 21{,}0 ^\circ\text{C} (\text{Soll}) - 20{,}0 ^\circ\text{C} (\text{Default-Ist}) = \mathbf{1{,}0 ^\circ\text{C}}$$
  Bei $1{,}0 ^\circ\text{C}$ Differenz entsprach der Wert exakt dem Stützpunkt 2 der Kennlinie mit **2 Stunden Vorlauf**:
  $$09:00\text{ Uhr} - 2\text{ Stunden} = \mathbf{07:00\text{ Uhr}}$$

### Ursache B: `ClassCastException` / `NullPointerException` beim Konfigurationsabruf
* **Code-Stelle:** `RoomHeaterHandler.java` in `evaluateHeatingLogic()`
* **Problem:** Die 8 Kennlinien-Parameter (`deltaT1-4`, `vorlauf1-4`) wurden direkt gecastet:
  `((BigDecimal) getConfig().get("deltaT4")).doubleValue();`
* openHAB speichert UI- oder Text-Eingaben ganzer Zahlen (`4` oder `6`) oft als `Integer`, `Long` oder `Double`. Der direkte Cast zu `BigDecimal` wirft eine `ClassCastException`. War ein Wert `null`, folgte eine `NullPointerException`.
* **Folge:** Sobald die Exception geworfen wurde, sprang die Methode in `catch (Exception e)`. Vorlaufberechnung und Relaissteuerung brachen komplett ab.

### Ursache C: Status-Updates vs. Commands in openHAB
* KNX-Sensoren senden in openHAB `postUpdate` (Statusaktualisierung), keine Befehle (`sendCommand`).
* Ohne das **Follow-Profil** (`[profile="follow"]`) in der Channel-Verknüpfung der Items wird `handleCommand` im Binding bei eingehenden Sensorwerten nicht aufgerufen.

### Ursache D: Fehlende Division-by-Zero Absicherung bei der Interpolation
* Hatten zwei benachbarte Stützpunkte der Kennlinie denselben Delta-T-Wert (`dt4 == dt3`), führte `(deltaT - dt3) / (dt4 - dt3)` zu `Double.NaN`, was in 0 Minuten Vorlauf resultierte.

---

## 3. Durchgeführte Änderungen & Behebungen

### Datei: `src/main/java/org/openhab/binding/churchtoolsheating/internal/RoomHeaterHandler.java`
1. **Robuster Parameterabruf:**
   * Alle 8 Parameter (`deltaT1-4`, `vorlauf1-4`) werden nun über `getDoubleConfig(key, defaultVal)` abgerufen.
   * Sicher vor `ClassCastException` (unterstützt `BigDecimal`, `Double`, `Integer`, `Long`, `String`) und liefert definierte Fallbacks bei `null`.
2. **Breite Typ-Unterstützung für Temperatur-Channels:**
   * In `handleCommand`: `currentTemperature` und `targetTemperature` akzeptieren nun `QuantityType`, `DecimalType` und `StringType`.
   * Ruft anschließend `updateState` auf, damit openHAB UI und Items synchron bleiben.
3. **Absicherung der linearen Interpolation:**
   * Division durch Null wird mit `(dtX > dtY) ? ... : fallback` verhindert.
4. **Erweitertes Debug-Logging:**
   * Jeder Berechnungsschritt protokolliert:
     `logger.debug("Heating evaluation: targetTemp={}, currentTemp={}, deltaT={}, vorlaufH={}h ({} min), eventStart={}, vorlaufZeit={}", ...)`

### Datei: `src/main/java/org/openhab/binding/churchtoolsheating/internal/ChurchToolsAPI.java`
1. **Erweitertes Datums-Parsing (`parseDate`):**
   * Erkennt nun auch ISO-Strings ohne Zeitzonen-Suffix oder mit Leerzeichen (`LocalDateTime`).
2. **Abruf-Zeitfenster ab Vortag:**
   * `from_date` und `from` starten jetzt bei `LocalDate.now().minusDays(1)`, um auch Termine über Mitternacht oder in den frühen Morgenstunden sauber zu erfassen.
3. **Cross-Version API-Parameter:**
   * Sendet sowohl `from_date`/`to_date` als auch `from`/`to`.

---

## 4. Build-Artefakt & Deployment

* **Kompiliert mit:** Maven 3.9 + Adoptium JDK 21
* **Befehl:** `mvn clean package -DskipTests` (Erfolgreich, BUILD SUCCESS)
* **JAR-Pfad:**
  `target/org.openhab.binding.churchtoolsheating-5.2.1-SNAPSHOT.jar`

### Installations-Schritte in openHAB:
1. Kopieren der Datei `org.openhab.binding.churchtoolsheating-5.2.1-SNAPSHOT.jar` in den openHAB `addons/`-Ordner.
2. Ältere Versionen des ChurchTools-JARs im `addons/`-Ordner löschen/überschreiben.
3. openHAB aktualisiert das Bundle automatisch im laufenden Betrieb.
4. **Alle bestehenden Konfigurationen (Things, Items, Kennlinien, Widgets) bleiben unverändert erhalten.**

### Wichtige openHAB Item-Konfiguration:
Sicherstellen, dass der Channel der Ist-Temperatur das Follow-Profil nutzt, falls der Messwert von einem KNX-Sensor oder anderem Binding stammt:
```java
Number:Temperature Raum_Saal_currentTemperature "Ist-Temperatur [%.1f °C]" { 
    channel="knx:device:bridge:saal:currentTemp",
    channel="churchtoolsheating:roomHeater:mychurch:saal:currentTemperature"[profile="follow"]
}
```

---

## 5. Update & Fehlerbehebung (10. November 2026)

### 5.1 Problemstellungen & Symptome
1. **Seminarraum schaltete nach Handbetrieb nicht aus:**
   * Beim Umschalten am KNX-Taster auf Nacht (3) sendete der Raumthermostat automatisch die Nachttemperatur (z. B. 12 °C). Die Temperaturänderung wurde fälschlicherweise als erneuter manueller Sollwert-Handbetrieb interpretiert.
2. **Automatik meldete nach Umschalten fälschlich Handbetrieb:**
   * Wenn die Automatik für einen anstehenden Termin auf Komfort (1) schaltete, interpretierte das Binding die KNX-Statusmeldung als manuellen Wandtaster-Klick und aktivierte die Handbetriebs-Sperre (6h).
3. **openHAB MainUI Widget reagierte nicht auf Komfort / Standby:**
   * Befehle aus dem Widget wurden durch eine Deaktivierung der externen KNX-Handerkennung abgewürgt und von der Automatik sofort wieder auf Nacht (3) zurückgesetzt.
4. **100% CPU-Last & KNX-Gateway-Verbindungsverlust (Der Event-Loop-Vorfall):**
   * Die CPU von openHAB stieg auf 100%, das KNX-IP-Gateway verlor durch Telegramm-Überflutung die Verbindung und die ChurchTools Bridge schaltete auf `DISABLED`.

---

### 5.2 Ursachenanalyse & Architektur-Erkenntnisse

#### A. Die Ursache des Event-Loops (100% CPU & KNX Absturz)
* In `ChurchToolsHandlerFactory.java` war das Binding als openHAB `EventSubscriber` für folgende Event-Typen registriert:
  `ItemStateChangedEvent.TYPE`, `ItemStateEvent.TYPE`, `ItemCommandEvent.TYPE`.
* **Der fatale Zirkelbezug:**
  Sobald das Binding einen Befehl an openHAB sandte (`getCallback().postCommand(...)`), generierte openHAB ein `ItemCommandEvent`.
  Die Factory fing diesen *eigenen* Befehl wieder ab, leitete ihn an `processChannelState` weiter, rief `evaluateHeatingLogic()` auf, sendete erneut `postCommand` – **eine unendliche Schleife mit tausenden Befehlen pro Sekunde**.
  Das KNX-IP-Gateway erlitt einen Pufferüberlauf (Buffer Overflow) und trennte die Verbindung.

#### B. Unterscheidung zwischen Automatik-Heizen und Handbetrieb
* Ein Tastendruck am KNX-Wandtaster erzeugt ein **`ItemStateChangedEvent`** auf dem KNX-Item.
* Das Binding verwaltet intern den erwarteten Zustand (`expectedHvacMode`, `expectedActuatorTargetTemp`, `targetRelayState`).
* **Unterscheidung:**
  * Wenn die Automatik heizt (`targetRelayState == ON`) und die Rückmeldung `1` (Komfort) eintrifft, ist das der reguläre Ablauf -> **kein Handbetrieb**.
  * Wenn die Automatik im Ruhezustand ist (Relais OFF, Nachtmodus 3) und die Meldung `1` (Komfort) oder `2` (Standby) oder ein erhöhter Sollwert eintrifft, stammt dies vom **Wandtaster** -> **Handbetrieb für 6h aktivieren**.

#### C. Unterscheidung zwischen Widget-Klick und KNX-Bus
* Ein Klick im MainUI-Widget ruft `handleCommand(channelUID, command)` auf.
* Dies wird als `isExplicitCommand = true` markiert:
  * Manuelle Klicks im openHAB Widget greifen **immer**, unabhängig davon, ob die Checkbox "KNX Handbetrieb erlauben" aktiv ist.
  * Der neue Modus wird sofort im openHAB-Item bestätigt und an den KNX-Bus weitergeleitet.

---

### 5.3 Implementierte Lösungen

#### Datei: `src/main/java/org/openhab/binding/churchtoolsheating/internal/ChurchToolsHandlerFactory.java`
* Registrierung auf den EventBus auf **ausschließlich** `Collections.singleton(ItemStateChangedEvent.TYPE)` reduziert.
* `ItemCommandEvent` und `ItemStateEvent` vollständig entfernt. Eigene Befehle werden niemals abgefangen; der Event-Loop ist dauerhaft ausgeschlossen.
* Dynamische Anbindung an die openHAB `ItemChannelLinkRegistry`: Verknüpfte KNX-Items werden out-of-the-box ohne manuelle Rules oder Profile erkannt.

#### Datei: `src/main/java/org/openhab/binding/churchtoolsheating/internal/RoomHeaterHandler.java`
1. **Explizite vs. Externe Befehle (`isExplicitCommand`):**
   * `handleCommand`: Übergibt `isExplicitCommand = true` (Widget / Rule / REST-API).
   * `receive` / `onItemNameStateChanged`: Übergibt `isExplicitCommand = false` (KNX-Bus).
2. **Sollwert-Handbetrieb (`CHANNEL_ACTUATOR_TARGET_TEMP`):**
   * Löst Handbetrieb nur aus, wenn die Automatik im Ruhezustand ist (`targetRelayState == OFF`) und die Temperatur über `offTemperature + 0.4 °C` gedreht wird.
   * Wird die Temperatur auf oder unter `offTemperature` (z. B. 12 °C oder 17 °C) abgesenkt, wird der Handbetrieb sofort beendet und das Relais ausgeschaltet.
3. **HVAC-Modus-Handbetrieb (`CHANNEL_HVAC_MODE`):**
   * **Außerhalb eines Termins (`targetRelayState == OFF`):**
     * Modus 1 (Komfort) oder 2 (Standby) aktiviert 6 Stunden Handbetrieb (`manualOverrideUntil`), schaltet Relais `ON`.
     * Modus 3 (Nacht) oder 4 (Frost) löscht `manualOverrideUntil` und schaltet Relais `OFF`.
   * **Während eines aktiven Termins (`targetRelayState == ON`):**
     * Modus 3 (Nacht) oder 4 (Frost) aktiviert einen **manuellen AUS-Handbetrieb** für 6 Stunden: Relais bleibt `OFF`, Modus bleibt `Nacht (3)`, die Automatik wird gesperrt und schaltet nicht mehr sofort wieder auf Komfort zurück (kein Hin- und Herspringen!).
     * Modus 1 (Komfort) beendet den AUS-Handbetrieb (`manualOverrideUntil = null`) und nimmt das normale Heizen für den Termin wieder auf.
   * Bei regulärem Heizbeginn der Automatik (`manualOverrideUntil == null && targetRelayState == ON && mode == 1`) wird kein Handbetrieb ausgelöst.
4. **Timeout-Rückkehr:**
   * Nach Ablauf der 6 Stunden kehrt der Raum automatisch zum regulären Zeitplan zurück.

#### Datei: `src/main/resources/OH-INF/thing/thing-types.xml`
* `enableExternalManualMode`: Checkbox "KNX Handbetrieb erlauben" (Default: `true`).
* `externalManualModeDuration`: Dauer in Stunden (Default: `6`).
* Optionale direkte Item-Zuordnung: `knxHvacModeItem`, `knxTargetTempItem`, `knxCurrentTempItem`.

---

## 6. Wichtige Wiederherstellungspunkte (Git Recovery Points)

Falls in künftigen Chat-Verläufen ein bestimmter Stand wiederhergestellt werden soll:

| Commit | Beschreibung | Zustand |
|---|---|---|
| **Aktuell (HEAD)** | **Manueller AUS-Handbetrieb während Termin:** Kein Hin- und Herspringen mehr bei Nacht-Schaltung während Termin, Loop-Fix, stabiles KNX-Gateway, 6h Handbetrieb via KNX & Widget. | **Empfohlen / Produktiv** |
| `e166536` | Stand mit Loop-Fix und EventSubscriber-Bereinigung. | Stabil, aber Nacht während Termin setzte Override noch zurück. |
| `dd5dbbe` | Stand vom 10.11.2026 morgens (vor dem Loop-Fix). | Stabil, aber noch ohne die Widget-Entkopplung. |
| `1b46a3a` | Stand vor Einführung des EventSubscribers. | Rein kanalbasiert ohne direkte EventBus-Lauschung. |

### Build-Befehl zur Wiederherstellung:
```powershell
& "C:\Users\jkid\.m2\wrapper\dists\apache-maven-3.9.16\0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0\bin\mvn.cmd" clean package -DskipTests
```
Artefakt: `target/org.openhab.binding.churchtoolsheating-5.2.1-SNAPSHOT.jar`


