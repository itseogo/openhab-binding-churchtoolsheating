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

