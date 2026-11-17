# VystormInsomnia: technische Details

Ergänzt die [README](../README.md).

## Core-Anbindung

Beim Start prüft das Plugin per `CoreApi.has(...)` die Capabilities `core-api`, `settings`, `chat-actions`, `regions`, `localization`, `task-groups`, `versioned-store`, `tutorials`, `task-group-one-shots`, `managed-worlds` und `reward-pouch` (Core 0.32.0). Laufzeit-Timer, Sekundenschlaf und Sichtverlust planen über Task-Gruppen. Fehlt eine Capability, deaktiviert sich das Plugin mit einer Meldung samt laufender Core-Version. `storage.type: mysql` braucht zusätzlich `shared-database` (Core 0.14.0+).

## Traumweite

Traumreisen verwenden Cores `ManagedWorlds` mit dauerhaftem Eintrittssnapshot, gesperrtem Besuchsinventar, isoliertem XP und einem markierten Rückkehrwerkzeug. Die Traumwelt wird aus `DreamWorldGenerator` aufgebaut und Core verwaltet sie als löschbare Welt anhand des Layout-Hashes. Das Insomnia-Runtime-System kontrolliert nur die drei begrenzten Traumorte, deren Sitzungslaufzeit und temporäre Nachtmahr-Entities.

Sammelstellen schreiben über `RewardPouch.credit` mit deterministischer Sitzungs-/Dungeon-ID. Erst nach bestätigter dauerhafter Gutschrift gilt ein Ort als abgeschlossen. Fehlgeschlagene Schreibvorgänge werden mit derselben ID im gemeinsamen Runtime-Tick wiederholt; die Warnung ist je Beleg rate-limitiert. `/dreamloot` beansprucht jeweils eine vollständige Belohnung außerhalb eines laufenden oder noch nicht wiederhergestellten Core-Besuchs. Ein volles Inventar lässt die Beute in der Tasche.

Tagschlaf verlängert normales Ausruhen nur bei einem nicht abgebrochenen `NOT_POSSIBLE_NOW`-Betteintritt in einer normalen Welt. Eine Traumreise startet ausschließlich ausdrücklich über `/dream`; tatsächliche Offlinezeit wird nie als Erholung gezählt. Das Dream-Recovery verwendet dieselbe `SleepService`-Session wie normaler Schlaf, verlangt aber kein `Player.isSleeping()` und vergibt weder vollständigen Schlafreset noch Ausgeruht-Bonus.

Das RewardPouch-/Visit-Modell ist für einen einzelnen Backend-Speicher ausgelegt. Proxy-Wechsel oder gemeinsam beschreibbare Snapshot-Verzeichnisse während eines Besuchs sind nicht abgesichert.

Die BloodMoon-Anbindung liest per Reflection `at.vystorm.bloodmoon.api.BloodMoonApi` (Multiplikatoren für Schlafqualität und Insomnia-Spawns, in BloodMoon unter `integrations.insomnia`).

## Speicher

Gespeichert werden nur spielerbezogene Werte: Müdigkeit, Schlafschuld, Stimulanz-Toleranz und -Crash, Wachzeit, letzter Schlaf und Sekundenschlaf, Ausgeruht-Bonus. Weltgebundene Daten gibt es nicht. Insomnia-Mobs und Sekundenschlaf-Platzhalter sind flüchtig; die Rückkehr aus einem abgebrochenen Sekundenschlaf steht im PDC der Spielerdatei.

```yaml
storage:
  type: yaml            # yaml (Standard) | mysql
  table-prefix: vinsom  # Tabellen <präfix>_players und <präfix>_imports
  server-id: default    # Name dieses Servers im Netzwerk, je Server eindeutig setzen
  claim-wait-seconds: 10
```

- **yaml** (Standard, für Einzelserver): `plugins/VystormInsomnia/data/fatigue.yml`, ein Core-`VersionedStore` (JSON mit Schema-Version, crash-sicher und synchron geschrieben). Ein unlesbarer Speicher wird als `fatigue.yml.corrupt-<zeit>` gesichert und der Start abgebrochen, statt mit leeren Werten weiterzumachen. Seit 0.5.0 wird das alte `data/players.yml` (Core-`DataEntry`) nicht mehr gelesen und nicht migriert.
- **mysql**: gemeinsame Vystorm-Datenbank aus Core. Die Zugangsdaten stehen nur in `plugins/vystorm_core/config.yml` unter `database.shared` (siehe Core-README „Gemeinsame Datenbank“). Umschalten braucht einen Neustart. Alle Datenbankzugriffe laufen auf einem eigenen Thread. Spieler werden beim Join geladen, beim Quit gespeichert, geänderte Werte zusätzlich alle `fatigue.dirty_flush_seconds`. Bis die Daten eines Spielers geladen sind, zählt Insomnia für ihn nichts (Befehle melden „wird noch geladen“).
- **Einmaliger Import:** Startet der MySQL-Modus mit leerer Spielertabelle und gibt es `fatigue.yml`, werden alle Spieler in einer Transaktion übernommen und in `<präfix>_imports` vermerkt. `fatigue.yml` bleibt als Backup und wird im MySQL-Modus nie beschrieben. Hat die Tabelle schon Daten (etwa von einem anderen Server), wird nichts importiert.
- **Serverwechsel:** Der Server, der einen Spieler geladen hat, hält ihn (Claim in der Zeile). Ein anderer Server wartet beim Join, bis der alte beim Quit gespeichert und freigegeben hat, höchstens `claim-wait-seconds`, und übernimmt dann (etwa nach einem Absturz, Meldung im Core-Debug-Log). Schreibversuche eines Servers, der den Spieler nicht mehr hält, werden abgelehnt. Beim Start gibt ein Server Claims frei, die ein früherer Lauf mit derselben `server-id` hinterlassen hat (nicht bei `default`).
- **Ausfall:** Ist die Datenbank im MySQL-Modus nicht verfügbar (Core-Pool aus oder fehlgeschlagen), deaktiviert sich das Plugin mit einer Meldung im Core-Debugger, statt still in `fatigue.yml` zu schreiben. Scheitert später ein Speichern, wird es dreimal versucht; periodische Saves werden beim nächsten Flush wiederholt, ein gescheiterter Quit-Save ist verloren (Log-Eintrag).
- **Grenzen:** Ein Absturz verliert wie bei YAML die Änderungen seit dem letzten Flush. Ein Spieler, der ohne Proxy gleichzeitig auf zwei Servern online wäre, wird nach `claim-wait-seconds` übernommen; der alte Server speichert ihn dann nicht mehr.

Integrationstests gegen eine lokale MariaDB laufen mit `VYSTORM_TEST_DB_HOST` (nur 127.0.0.1/localhost), `_PORT`, `_NAME`, `_USER`, `_PASSWORD`. Ohne diese Variablen werden sie übersprungen.

## Texte

Alle Spielertexte kommen aus Cores Sprachsystem (`api.lang()`): `lang/de.yml` und `lang/en.yml` im Jar, beim ersten Start nach `plugins/VystormInsomnia/lang/` kopiert. Jeder Spieler bekommt seine Sprache (persönliche Wahl in `/vsettings`, sonst Cores `language.default`). Fehlende Schlüssel kommen aus dem Jar, Englisch ist der letzte Rückfall. `/insomnia admin reload` liest auch die Sprachdateien neu. Die Beschriftungen der Server-Einstellungen (`setting.<config-pfad>.label|desc`) stehen ebenfalls dort.

`messages.yml` (bis 0.4.x) gibt es nicht mehr und wird nicht migriert. Eigene Texte nach `lang/de.yml` übernehmen (gleiche Schlüssel; das Präfix steht ohne abschließendes `&7` unter `prefix`).

## Einstellungen und Config-Editor

Die Server-Einstellungen „Müdigkeit & Schlaf“ (`vystorminsomnia:server`, Recht `insomnia.admin`) enthalten die Schalter für Müdigkeit (`fatigue.enabled`), Stimulanzien, Halluzinationen, Sekundenschlaf, Insomnia-Mobs und Ausgeruht-Bonus sowie die wichtigsten Raten. Die Werte landen in `config.yml` und werden sofort neu geladen; die Integrations-Schalter greifen erst nach einem Neustart.

Ab Core 0.27.0 sind `config.yml`, `stimulants.yml` und `mobs.yml` vollständig unter `/vadmin config VystormInsomnia` bearbeitbar (Recht `vystorm_core.config.vystorminsomnia`); ältere Cores überspringen das. Die 23 Werte des Einstellungsabschnitts tragen dieselben Beschriftungen, `config_version` ist ausgeblendet. Speichern prüft alle drei Dateien; eine ungültige Änderung wird abgelehnt, die alte Konfiguration bleibt aktiv, und der Admin bekommt den Grund. Core ergänzt beim Start fehlende Standardeinträge auch in `stimulants.yml` und `mobs.yml` (mit Sicherung).

## Inaktivität, Verlauf und Splitter

- `fatigue.pause-afk` (Standard aus, 300 Sekunden, Minimum 60): Blockwechsel, deutliche Blickänderungen, Interaktionen, Kampf oder Schaden, Bettaktionen und Chat setzen den Timer zurück. Das ist eine lokale Eingabepause und meldet keinen AFK-Status an andere Plugins. Der Status zeigt den Pausengrund.
- Der Verlauf ist auf 24 Einträge begrenzt, der persönliche Verlauf zeigt die neuesten acht. Er wird bei Logout und Neustart gelöscht. `/insomnia triggers` erklärt die zuletzt ausgelösten Halluzinationen anhand ihrer damaligen Stufe, Müdigkeit und Schlafschuld.
- Echo-Splitter werden am Mob-Drop-Punkt je Besitzer über ein rollendes Fenster begrenzt: Standard höchstens drei, die Chance halbiert sich nach der ersten Belohnung, Fenster 15 Minuten. Das Limit zählt tatsächlich erzeugte Stücke (höchstens 1.000 pro Fenster, Fenster höchstens 24 Stunden), überlebt Ab- und Anmelden, gilt aber nur für den laufenden Server. Andere Drops bleiben unverändert.

## Sekundenschlaf

Während eines Sekundenschlafs wird der Spieler zum Zuschauer mit einer Kamera, sein Körper bleibt als Platzhalter stehen und leitet Schaden weiter. Er startet nur auf festem Boden, nicht in Fahrzeugen, Flüssigkeiten oder im Kampf, weil der Spieler danach an den Startpunkt zurückkehrt. Teleports mit Ursache SPECTATE gehören zur Sitzung, jeder andere beendet sie an Ort und Stelle.

`microsleep.recovery_grace_seconds` gibt nach einem unterbrochenen Sekundenschlaf einmalig 0 bis 300 Sekunden Schonfrist (Standard 30). Normales erneutes Einloggen verlängert sie nicht und setzt weder Müdigkeit noch Schlafschuld zurück. `/insomnia status` zeigt die Restzeit. Spielmodus und Flugrechte werden vor dem Rückteleport repariert. Fehlt die Welt oder wird der Teleport abgelehnt, bleibt der Rückkehr-Snapshot für den nächsten Login erhalten und weiterer Sekundenschlaf ist gesperrt. Welten werden nie zwangsweise geladen.

## Client-Mod

Über Core `ClientUi`/`Panels` (Capabilities `web-client-ui`, `panels`):

- **HUD oben rechts:** Müdigkeitsbalken (Wert, Stufe, Farbe je Stufe) und Schlafschuld-Balken, aktualisiert nur bei Änderung (höchstens einmal pro Sekunde).
- **Toast bei Stufenwechsel** (Titel = Stufe, Text = Stufen-Meldung, Stil nach Schwere); dann entfällt die Chat-Meldung. Ohne Mod oder mit ausgeblendeter UI kommt die Chat-Meldung mit [Müdigkeit]-Knopf.
- **Panel „Müdigkeit“** (`vystorminsomnia:fatigue`, auch über `/vmenu` → Charakter und `/insomnia`): Zustand, Fortschrittsbalken, Ausgeruht-Bonus, Stimulanz, Schlaf, Hinweis. Admins bekommen einen Knopf, der Cores Einstellungs-Panel direkt auf dem Insomnia-Abschnitt öffnet (`Button.openSettings`).

Mit Client 0.9.0 stapeln sich die Balken mit anderen HUD-Elementen (`stack`). Ein dritter Balken zählt den Ausgeruht-Bonus auf dem Client herunter (`Bar.countdown` mit `{time}`, einmal je Bonus gesendet). Ältere Clients bekommen die Balken mit festen Abständen und keinen Bonus-Balken. Toasts gibt es nur bei sichtbarer UI (`ClientUi.visible`). Texte: `hud.*`, `panel.*` in `lang/`. Ohne Client-Kanal (`web.client.enabled: false`) läuft alles wie ohne Mod. Tests rendern das Panel samt Fußleiste mit `PanelTesting`.

## Webseite

Modul `insomnia` in Cores Web-UI (`/app/insomnia`, auch im Overlay der Client-Mod; Capabilities `web-live`, `web-open`, Recht `insomnia.use`): Zustand, Balken, Ausgeruht-Bonus, Stimulanz, Schlaf, Hinweis, Stufengrenzen und der Verlauf der aktuellen Sitzung als Diagramm (ein Messpunkt pro Minute, höchstens 180, nur im Speicher). Live per `WebServices.push`, nur bei Änderung.

- `GET /api/insomnia/status`
- `GET /api/insomnia/history`

Erreichbar über `/web insomnia`, den Knopf „Web-Seite“ im Müdigkeits-Dialog und „Webseite öffnen“ im Panel. Mit `web.enabled: false` fehlt nur die Seite. Texte: `web.module.*`, `web.ui.*` in `lang/`.


### Paper 26.2 daytime wake compatibility

Vanilla immediately wakes players during daytime even after an allowed bed-enter event. The isolated `compat/paper26/BedLeaveOrigin` seam recognizes only the direct `Player.tick -> ServerPlayer.stopSleepInBed` call path on Minecraft 26.2. It uses JDK StackWalker and links/invokes no server internals. All unknown paths and versions allow waking. Client Leave Bed, `/wake`, plugin teleports and damage remain valid exits. Day sleepers do not count toward skipping world time. `GameplayListener` must ignore cancelled bed-leave events so cancellation cannot end recovery or trigger per-tick writes. This adapter needs a new real-client acceptance test before supporting a different Minecraft version.

The 0.14.0 landscape generator evaluates an immutable, Bukkit-independent `DreamTerrain` first. Domain-warped fractal noise defines land/void topology and a shared 3D density lattice defines rock, overhangs and interior holes. Complete solid/air occupancy is retained as column bitsets; there are no island-radius templates. The current finite single representation occupies x/z ±256 and y64..255. There is no offset world or mirrored ceiling.

Two independent warped temperature/moisture fields select five broad biome regions in climate space. A seed-dependent translation of the complete noise domain places the origin on naturally supported CLOUD terrain. Boundary fading remains tied to world coordinates. `DreamWorldPlan` only surveys representative biome locations; it neither places structures nor shapes land. The generator levels a five-by-five safe arrival area; dungeon foundations and carving are restricted to surveyed footprints. Ground cover, trees and sparse low mineral clusters follow the biome field. A BiomeProvider maps the same field to Meadow, Dark Forest, Stony Peaks, Flower Forest and Dripstone Caves for consistent native grass/foliage tint. The older fixed architecture and tower/transfer layout remain inactive. Procedural dungeon plans and natural walking trails are generated separately as described below.

Dream visits still use Core snapshots, fixed deadlines and normal `/wake` return. The default layout exposes three dungeon sites and no towers. Personal encounters and puzzle rewards use the existing RewardPouch; disabling dungeons leaves both layout lists empty. Exploration of disconnected terrain is a design-stage limitation; this version does not add traversal mechanics or promise every biome is walkable from spawn. No new runtime task or async Bukkit access is added. Configuration changes require restart; layout version/hash changes rebuild only the managed disposable dream world, never the production main world.

Terrain relief blends broad continental variation, shorter rolling hills and rounded ridge noise using continuous climate-space weights. The bounded smooth height envelope avoids flat clipped summits; complete density and cave occupancy remain intact underneath. Natural terrain is sampled through y164 with an extra lattice neighbour; the two-long column mask supports y64..191, leaving canopy headroom. The generator exposes rock on steep slopes, anchors mineral columns to their own surface and protects the underlying density field from vegetation writes. Arrival height follows the generated shelf.

Dream visits own a `DreamTimeBar`. The existing master tick refreshes its localized title/progress at most once per second using the immutable visit deadline; cleanup detaches all viewers on exit, disconnect and shutdown. No additional task or persisted timer is introduced.

Zusätzliche schwebende Inseln entstehen aus einem unabhängigen, domain-verzerrten 3D-Dichtefeld (Y150–222), mit mindestens 24 Luftblöcken über dem unteren Gelände und freiem Ankunftsbereich. Es gibt keine Inselzentren, Schablonen oder feste Landmarken. Pflanzen und Mineralrücken folgen zusammenhängenden Noise-Masken; hängende Wurzeln wachsen aus den tatsächlich generierten Unterseiten. Material und Vegetation richten sich nach demselben Biomfeld wie unten. `surfaceY` bleibt bewusst die untere Landschaftsoberfläche für Ankunft und Biommarker; Chunk-Ausgabe und Höhenabfrage enthalten beide Schichten. Höhenbudget einschließlich Bewuchs: Y255.

`dream.world.sky_island_density` (Standard 100) und `dream.world.natural_accent_density` (Standard 65) regeln die Schichten separat von `decoration_density`, jeweils 0–100; 0 schaltet die betreffende Schicht ab. Änderungen benötigen einen Neustart und gehen in den Layout-Hash ein. Der aktuelle Layout-Hash lässt Core die wegwerfbare Traumwelt erneuern; keine Änderung an Spielerpersistenz oder Besuchsfrist. Die Erreichbarkeit der oberen Inseln ist noch keine neue Spielmechanik.

`DreamObjectGenerator` erzeugt zusätzliche Naturobjekte als unveränderliche Voxelvorschläge auf beiden Geländeschichten. Jeder Vorschlag wird vollständig auf Kollisionen geprüft, bevor ein Block geschrieben wird. Bestehende feste Blöcke und die Ankunftszone bleiben frei von Überschreibungen. `dream.world.natural_object_density` (0–100, Standard65) regelt diese Schicht unabhängig von Inseln und Bewuchs. Der Layout-Hash berücksichtigt die Einstellung. Es gibt keine zusätzlichen Entities, Scheduler oder Persistenzdaten.

`DreamGroundCover` liefert reine seedbasierte Oberflächen- und Unterwuchsmasken. Die letzte Dekorationsphase prüft alle vier benachbarten Höhen, erhält bestehende Objekte und setzt Pflanzen nur auf geeignetem Boden. Zusammenhängende Flecken ersetzen ausschließlich Oberflächenmaterial; das innere Dichtefeld bleibt erhalten. Flache geschwungene Senken gehören zum kontinuierlichen Höhenfeld und werden auch bei der Spawn-Auswahl berücksichtigt. Steuerung des Unterwuchses über `natural_accent_density`.

## Procedural dungeons (0.14.0)

`DreamDungeonGenerator` emits immutable room/floor/wall/air plans from seed and type. `DreamDungeonPlacement` surveys the naturally walkable lower-land component and chooses supported, disjoint sites, preferring matching biomes. Footprints stay within six blocks of entrance height, with a bounded ten-block fallback. The generator creates foundations and clears room volume only inside each footprint. A BFS trail avoids prior sites, retains natural one-block steps and is protected from later decoration. Final-voxel tests verify every objective is reachable.

Cloud Ruins track four personal waypoints before the golden stone; Crystal Vault requires four ordered right-clicks; Nightmare Keep starts configured personal enemy waves at the arena center. Directions and entry hints use Core language bundles. Rewards retain existing session/dungeon receipt IDs and Core RewardPouch storage; no schema or transfer changes. Layout version 17 and `dream.world.dungeons_enabled` participate in restart/rebuild detection.

## Core-Loot (ab Core 0.44.0)

Die Drops der Albtraum-Mobs laufen über das gewichtete Core-Lootsystem: Jede Stufe in `mobs.yml` wird zur eigenen Tabelle `vystorminsomnia:mobs/tier_<id>` (Modus `each`, gleiche Chancen und Mengen, Mengen über 64 werden auf einen Stack begrenzt) und wird über `category:mob_drop_common` gewürfelt. `tiers.<id>.loot_table` kann pro Stufe eine andere Core-Tabelle oder Kategorie nennen (leer = Kategorie). Drops bleiben dem Besitzer vorbehalten, das ECHO_SHARD-Fensterlimit gilt weiter (als zweite Prüfung, gleiche Gesamtchance). Ohne Core 0.44.0 gilt der eingebaute Wurf. Die Belohnungen der Traum-Dungeons sind feste Listen und bleiben unverändert.
