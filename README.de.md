# VystormInsomnia

Wer lange wach bleibt, wird müde. VystormInsomnia gibt jedem Spieler einen Müdigkeitswert, der beim Spielen langsam steigt und nur durch echten Schlaf im Bett wieder sinkt. Je müder du bist, desto mehr spielt dir dein Kopf Streiche: Schritte hinter dir, ein Creeper, der nicht da ist, kurze Aussetzer. Wer gut schläft, wird mit einem Ausgeruht-Bonus belohnt.

## Funktionen

- **Schlafschuld-Erinnerungen (0.21.0).** Ab 50 % Schlafschuld erinnert dich eine Chatnachricht ans Erholen im Bett – auch wenn Kaffee oder Lagerfeuer deine Müdigkeit niedrig halten. Höchstens alle 15 Minuten pro Sitzung; Schlaf, Traumreisen, Sekundenschlaf, Tod und pausierte Müdigkeit unterbrechen die Hinweise. Unter `/vsettings` → Schlafen kannst du sie dauerhaft abschalten. Schwelle und Abstand sind für Serverbetreiber einstellbar.
- **Müdigkeit und Schlafschuld.** Zwei Werte von 0 bis 100. Die Müdigkeit steigt beim normalen Spielen um etwa eine Stufe alle 160 Minuten, schneller beim Sprinten, Schwimmen, Abbauen und im Kampf, doppelt so schnell gegen Bosse. Die Schlafschuld wächst langsamer und baut sich auch langsamer wieder ab.
- **Fünf Zustände.** Ausgeruht, Müde (ab 20), Erschöpft (ab 40), Übermüdet (ab 60) und Schlafentzug (ab 80). Beim Wechsel gibt es eine Meldung. `/insomnia status` zeigt die nächste Stufe und ungefähr, wie lange es bei normalem Spielen noch dauert (0.16.0).
- **Richtig schlafen.** Im Bett durchläufst du Einschlafen, leichten und tiefen Schlaf, das dauert etwa eine Minute. Das zählt unabhängig davon, ob die Nacht übersprungen wird. Wie gut du schläfst, hängt vom Ort ab: Unter einem Dach, im Dunkeln und in einer Stadt erholst du dich besser, unter freiem Himmel, bei Gewitter oder mit Monstern in der Nähe schlechter.
- **Natürliche Traumlandschaft (0.14.0).** Ein 3D-Geländegenerator erzeugt schwebende Felslandschaften mit sanften Hügeln, bewaldeten Rücken, felsigen Erhebungen, Tälern, Überhängen und Höhlen. Zusätzliche Inselgruppen schweben darüber, mit eigenem Bewuchs, hängenden Wurzeln und unregelmäßigen Mineralrücken. Umgestürztes Holz, verwitterte Felsgruppen, Pilzgruppen und Mineralformationen ergänzen die Landschaft. Flache, geschwungene Senken, Farn- und Blütenfelder, niedrige Azaleen und fleckige Waldböden beleben die Flächen dazwischen. Auch diese Formen entstehen vollständig aus dem Seed. Fünf zusammenhängende Biomtypen prägen Gelände und Vegetation: offene Wolkenwiesen, dichter Spiegelhain, helle Kristallfelsen, grüne Dämmergärten und karger Nachtbruch. Drei prozedurale Dungeons ergänzen die Landschaft: Wolkenruinen zum Erkunden, ein Kristallgewölbe mit Reihenfolgerätsel und eine Nachtmahrfeste mit persönlichen Gegnerwellen. Begehbare Erdspuren verbinden sie mit dem Start. `/dream` zeigt Besuchern die Eingänge erneut; Beute wird im geschützten Traumbeutel gesammelt.
- **Traumweite.** Tagsüber ist normales Ausruhen im Bett möglich. Ab 40 Müdigkeit kannst du mit `/dream` nahe eines sicheren Bettes eine begrenzte Erkundungsreise starten. Mehr Müdigkeit verlängert die beim Eintritt festgelegte Zeit bis auf 20 Minuten. `/wake` bringt dich zurück. Dungeonbeute bleibt im Traumbeutel erhalten und lässt sich nach der Rückkehr mit `/dreamloot` abholen. Traumreisen geben nur die tatsächlich verbrachte Zeit als Teilerholung und keinen Ausgeruht-Bonus.
- **Gemütliches Schlafzimmer (0.15.0).** Einrichtung im Umkreis von 4 Blöcken ums Bett verbessert den Schlaf: Teppich oder Wolle, Topfpflanze, Bücherregal, Laterne oder Kerze, Bild oder Rahmen, Truhe oder Fass. Jede Art zählt einmal und bringt 4 % Schlafqualität, zusammen bis zu +24 %. Beim Hinlegen und mit `/insomnia status` neben dem Bett siehst du, was schon da ist und was noch fehlt.
- **Rast am Lagerfeuer (0.17.0).** Unterwegs und kein Bett in Sicht? Bleib ein paar Sekunden neben einem brennenden Lagerfeuer oder Seelenlagerfeuer stehen. Deine Müdigkeit sinkt dann langsam (0,2 pro Sekunde), aber nie unter 30 %. Die Aktionsleiste zeigt den Fortschritt. Bewegung, Kampf, Schlafen oder Kreativ-/Zuschauermodus beenden die Rast. Schlafschuld und Ausgeruht-Bonus gibt es weiterhin nur im Bett, das Lagerfeuer ist eine Notlösung und kein Ersatz.
- **Schlafübersicht für Admins (0.17.0).** `/insomnia admin overview` oder der Knopf „Alle Spieler“ im Müdigkeits-Menü listet alle Spieler, die müdesten zuerst, mit Stufe, Müdigkeit, Schlafschuld und ob jemand schläft oder am Lagerfeuer rastet. Ein Klick auf einen Spieler zeigt seine Werte und Schnellaktionen: ausschlafen lassen, Schlafschuld löschen, Müdigkeit −20 oder +20. Kein Befehl mit Spielernamen mehr nötig.
- **Spielermenü (0.17.0, Vystorm Core 0.43.0+).** Schleichen + Rechtsklick auf einen Spieler zeigt einen Müdigkeits-Eintrag: Admins sehen die Werte und kommen mit einem Klick zu den Schnellaktionen, alle anderen sehen nur, wie müde jemand aussieht (Stufe, keine Zahlen; abschaltbar).
- **Schlafdialog (0.18.0).** Wer sich nachts hinlegt, wählt in einem Dialog: schlafen bis zum Morgen (06:00), bis Mittag (12:00), bis zum Abend (18:00), bis zur nächsten Nacht (22:00) oder bis zu einer eigenen Uhrzeit, in die Traumweite gehen (nur sichtbar, wenn du gerade hineindarfst) oder aufstehen. Die Uhrzeiten gelten immer für den kommenden Tag. „Nicht mehr fragen“ macht die Wahl automatisch. Beim Mittagsschlaf gibt es eine kurze Fassung (weiter dösen, Traumweite, aufstehen), aber nur, wenn die Traumweite offensteht. Bedrock-Spieler (Floodgate) bekommen dieselben Knöpfe im Chat. Unter `/vsettings` → Schlafen stellt jeder ein, ob per Dialog, gar nicht (gespeicherte Wahl) oder im Chat gefragt wird.
- **Zeitraffer statt Nachtsprung (0.18.0).** Die Nacht wird nicht mehr übersprungen, die Zeit läuft schneller, je mehr Spieler einer Welt schlafen: Tempo = `min_speed + (max_speed − min_speed) × (Schläfer / Spieler) ^ curve`. Mit den Standardwerten 2, 10 und 1,0 heißt das: 1 von 4 schläft = 4×, 2 von 4 = 6×, alle = 10× (eine Nacht dauert dann etwa eine Minute, so lange wie ein voller Insomnia-Schlaf). Alle in der Welt sehen in der Aktionsleiste „2/4 schlafen – Zeit läuft 6× schneller“. Die Uhr läuft auf die früheste gewählte Weckzeit zu, jeder wacht zu seiner eigenen Zeit auf, danach übernimmt die nächste. Regen und Gewitter hören am Morgen auf wie beim normalen Nachtsprung. Zuschauer und Spieler, die beim Schlafen nicht zählen (AFK-Plugins), werden nicht mitgezählt. Gamerules werden nicht verändert; Welten mit `players_sleeping_percentage` über 100 oder `advance_time` aus bleiben unberührt.
- **Ausgeruht-Bonus.** Nach einer vollständigen Schlafphase hast du 45 Minuten lang 5 % mehr XP, leichte Regeneration und weniger Hunger.
- **Kaffee und Energy-Drinks.** Senken die Müdigkeit sofort, aber mit jeder Tasse wirkt es schwächer, und danach kommt ein Absturz. Standardmäßig ist Kaffee eine Honigflasche. Weitere Stimulanzien legt man in `stimulants.yml` an. Seit 0.20.0 zeigt `/insomnia status` die Restdauer, Einnahmen seit dem Ausschlafen und die Müdigkeit nach dem Absturz anhand aktueller Werte (maximal 100 %). Aktivität und Schlaf können diese Prognose noch ändern.
- **Halluzinationen.** Ab „Erschöpft“ hörst du Gähnen, Schritte, Türen oder einen Creeper, siehst Schatten oder es wird kurz dunkel. Je müder, desto öfter. `/insomnia triggers` erklärt hinterher, welche Halluzinationen ausgelöst wurden und warum.
- **Sekundenschlaf.** Ab 80 Müdigkeit nickst du ab und zu für ein paar Sekunden weg. Dein Körper bleibt stehen und kann getroffen werden, du schaust kurz von außen zu. Das passiert nur auf festem Boden und außerhalb von Gefahr.
- **Insomnia-Mobs.** Ab 70 Müdigkeit tauchen Mobs auf, die nur dich angreifen und nur für dich Beute fallen lassen. Je müder, desto härter (drei Stufen, bis hin zu Vindicators). Auf der höchsten Stufe gibt es Echo-Splitter, höchstens drei in 15 Minuten.
- **Pausen.** Im Kreativ- und Zuschauermodus steigt die Müdigkeit nicht. Optional pausiert sie auch, wenn jemand länger nichts tut.
- **Verlauf.** `/insomnia history` zeigt deine letzten Werte und wann du geschlafen oder etwas getrunken hast. Der Verlauf gilt nur für die aktuelle Sitzung.
- **Blutmond.** Läuft BloodMoon, schläfst du während eines Blutmonds schlechter und Insomnia-Mobs kommen öfter.
- **Menü.** Hauptmenü (`/vmenu`) → Charakter → Müdigkeit, oder einfach `/insomnia`. Eine Anleitung steht unter Tutorials → Müdigkeit & Schlaf.
- **Client-Mod.** Mit der Vystorm-Client-Mod siehst du oben rechts Balken für Müdigkeit, Schlafschuld und die Restzeit des Ausgeruht-Bonus, Stufenwechsel kommen als Hinweis, und das Menü ist ein eigenes Panel. Ohne Mod läuft alles über Dialog und Chat.
- **Im Browser.** Läuft die Webseite des Servers, zeigt `/web insomnia` deinen Zustand und den Verlauf der Sitzung als Diagramm.
- **Deutsch, Englisch und vereinfachtes Chinesisch (zh_cn).** Alle Texte gibt es in diesen Sprachen, je nach Spielereinstellung.

## Installation

- Paper oder Purpur 1.20.6 bis 26.x; Java 21 für 1.20.6, auf neueren Servern deren erforderliche Java-Version (26.x: Java 25)
- Vystorm Core 0.64.0 oder neuer
- Optional: VystormCityClaim (besserer Schlaf in Städten und Mietobjekten), BloodMoon

Die JAR in `plugins/` legen und den Server starten. `config.yml`, `stimulants.yml`, `mobs.yml` und die Sprachdateien werden beim ersten Start angelegt.

Auf 1.20.6 erscheinen Menüs und Schlafauswahl als klickbarer Chat, da native Dialoge dort fehlen. Alle mitgelieferten Spielmechaniken bleiben verfügbar. Konfigurierte Materialien oder Mobtypen, die es auf dem Server noch nicht gibt, werden mit Diagnose übersprungen. Neuere Server behalten ihre nativen Dialoge.

Seit 0.5.0 werden zwei alte Dateien nicht mehr gelesen und nicht übernommen: `data/players.yml` (Spielerwerte) und `messages.yml` (eigene Texte gehören jetzt nach `lang/de.yml`, gleiche Schlüssel).

## Befehle und Rechte

| Befehl | Was er macht |
|---|---|
| `/vsettings` → Schlafen → Schlafschuld-Erinnerungen | Persönliche Erinnerungen dauerhaft an- oder abschalten (Core-Spielereinstellungen) |
| `/insomnia`, `/insomnia menu` | Müdigkeits-Menü öffnen |
| `/insomnia status` | Zustand, nächste Stufe mit geschätzter Zeit, Koffein-Restdauer und Absturz-Prognose, im Bett auch Schlafqualität und erwarteter Bonus |
| `/insomnia history` | letzte Werte und Ereignisse dieser Sitzung |
| `/insomnia history [seite]`, `/insomnia triggers [seite]` | Bis zu 24 gespeicherte Sitzungseinträge auf Seiten mit je acht Einträgen lesen, neueste zuerst |
| `/insomnia triggers` | zuletzt ausgelöste Halluzinationen erklären |
| `/insomnia admin overview` | Schlafübersicht aller Spieler mit Schnellaktionen (Konsole: Liste im Chat) |
| `/insomnia admin get <Spieler>` | Werte eines Spielers samt Koffein-Restdauer und Absturz-Prognose ansehen |
| `/insomnia admin setfatigue\|setdebt <Spieler> <0-100\|+n\|-n>` | Müdigkeit oder Schlafschuld setzen, mit `+10`/`-5` relativ zum aktuellen Wert |
| `/insomnia admin sleep <Spieler>` | Spieler sofort ausschlafen lassen |
| `/insomnia admin effect <Spieler> <Effekt>` | Halluzination auslösen |
| `/insomnia admin spawnmob <Spieler> <Stufe>` | Insomnia-Mob spawnen |
| `/insomnia admin reload` | Config und Sprachdateien neu laden |
| `/dream` | Nahe eines sicheren Bettes eine Traumreise beginnen (ab konfigurierbarer Müdigkeit) |
| `/wake` | Traumreise sicher beenden |
| `/dreamloot` | Eine Beute aus der dauerhaften Traumbeutetasche beanspruchen |
| `/vsettings` → Schlafen | Eigene Schlaf-Einstellungen: per Dialog fragen, gespeicherte Wahl ohne Nachfrage oder im Chat fragen; gespeicherte Wahl; eigene Weckzeit |

| Recht | Standard | Wofür |
|---|---|---|
| `insomnia.use` | alle | `/insomnia`, die Anzeige, Schlafschuld-Erinnerungen und die Stufe anderer Spieler im Spielermenü |
| `insomnia.admin` | OP | alle `admin`-Befehle, Schlafübersicht mit Schnellaktionen (auch im Spielermenü) und die Server-Einstellungen, enthält `insomnia.use` |
| `insomnia.dream` | alle | Traumreise, sicheres Aufwachen und Beute beanspruchen; ohne dieses Recht fehlt der Traumweite-Knopf im Schlafdialog |
| `insomnia.*` | OP | alles |

## Konfiguration

Admins können die Einstellungen im Spiel ändern: `/vadmin config VystormInsomnia` (Core 0.27, umfasst `config.yml`, `stimulants.yml` und `mobs.yml`) oder Hauptmenü → Einstellungen → Server-Einstellungen → Müdigkeit & Schlaf. Speichern wirkt wie `/insomnia admin reload`, eine ungültige Änderung wird abgelehnt. `storage.*` und die Schalter unter `integrations` brauchen einen Neustart.

Die wichtigsten Stellen in `config.yml`:

- `debt_reminder.enabled`: Schlafschuld-Erinnerungen einschalten (Standard `true`). `debt_reminder.threshold`: Mindest-Schlafschuld in Prozent (1–100, Standard `50.0`). `debt_reminder.interval_minutes`: Mindestabstand pro Spieler (1–1440 Minuten, Standard `15`). Änderungen wirken beim Reload; fehlende Schlüssel werden in bestehenden Dateien ergänzt. Der Abstand gilt pro Sitzung, die persönliche Abschaltung bleibt über Neustarts erhalten. Eine sinkende Schlafschuld oder kurzes Abschalten setzt den laufenden Abstand nicht zurück.
- `fatigue.*`: wie schnell Müdigkeit und Schlafschuld steigen, Stufengrenzen, Pausen
- `activity.*`: Faktoren für Sprinten, Abbauen, Kampf und Bosse
- `sleep.*`: Dauer der Schlafphasen, Erholung, Einfluss des Schlafplatzes
- `sleep.daytime.enabled`: Tagschlaf im normalen Bett erlauben
- `sleep.dialog.*`: Schlafdialog. `enabled` (Standard an), `day_naps` (auch beim Mittagsschlaf, Standard an), `presets.morning|noon|evening|night` (Uhrzeit hh:mm des kommenden Tages, Standard 06:00/12:00/18:00/22:00, `''` blendet einen Knopf aus), `custom_time` (eigene Weckzeit anbieten, Standard an)
- `sleep.acceleration.*`: Zeitraffer statt Nachtsprung. `enabled` (Standard an), `min_speed` (Standard 2.0) und `max_speed` (Standard 10.0, beide 1-100), `curve` (1.0 gleichmäßig, größer = erst schnell, wenn fast alle schlafen, 0.1-5), `worlds` (leer = alle Oberwelten außer der Traumwelt), `action_bar` (Anzeige für alle in der Welt, Standard an)
- `sleep.cancel_vanilla_night_skip`: Nachtsprung ganz verhindern, auch ohne Zeitraffer (Standard aus)
- `sleep.wake_summary`: Wer vor dem vollen Ausruhen aufsteht, sieht, wie lange er geschlafen hat und wie viel Müdigkeit und Schlafschuld es abgebaut hat (Standard an)
- `dream.*`: Eintrittsschwelle, Ursprungswelten, Zeitbudget, Warnungen, Welt, Belohnungen und Nachtmahr-Wellen
- `hallucinations.*`, `microsleep.*`, `insomnia_mobs.*`, `rested.*`: jedes System einzeln an- und abschaltbar
- `rest.campfire.*`: Rast am Lagerfeuer. `enabled` (Standard an), `radius` (Suchradius ums Lagerfeuer, 1-8, Standard 3), `settle_seconds` (so lange still stehen, Standard 5), `fatigue_per_second` (Standard 0.2), `min_fatigue` (darunter senkt das Lagerfeuer nie, Standard 30)
- `player_menu.enabled`: Müdigkeits-Eintrag im Core-Spielermenü (Standard an); `player_menu.show_stage_to_players`: Spieler ohne Admin-Recht sehen die Stufe anderer (Standard an)

Gelöschte Standard-Einträge in `stimulants.yml` oder `mobs.yml` (etwa `coffee`) ergänzt Core beim nächsten Start wieder. Wer einen davon loswerden will, sollte ihn umbauen statt löschen.

### Speicher

Gespeichert werden Müdigkeitswerte und die Traumbeutetasche über getrennte Core-Speicher. Traumweltbesuche und Inventar werden von Cores `ManagedWorlds` gesichert; Beuteübergaben laufen über `RewardPouch`. Das Feature nutzt lokalen Core-Speicher und ist nicht netzwerk-/proxy-sicher. Standard ist für Müdigkeit `storage.type: yaml` (Datei `plugins/VystormInsomnia/data/fatigue.yml`). Für ein Netzwerk gibt es `mysql` mit der gemeinsamen Datenbank aus Vystorm Core (Zugangsdaten in `plugins/vystorm_core/config.yml` unter `database.shared`). Dann braucht jeder Server eine eigene `storage.server-id`, damit Spieler beim Serverwechsel sauber übergeben werden.

Beim ersten MySQL-Start wird `fatigue.yml` einmal übernommen und bleibt als Sicherung liegen. Ist die Datenbank nicht erreichbar, schaltet sich das Plugin ab.

## Für Entwickler

Bauen mit JDK 25, einem lokal installierten Gradle 9.4.1 (das Repo hat keinen Wrapper) und Core 0.64.0 als Compile-Dependency. Alle Java-Quellen werden mit `--release 21` übersetzt:

```bash
gradle clean build --offline --warning-mode fail --max-workers=2 --no-daemon --project-cache-dir build/gradle-cache -PvystormCoreJar=/pfad/zu/Vystorm_Core.jar
```

Ausgabe: `build/libs/VystormInsomnia-<version>-26.2.jar`. Andere Plugins lesen und ändern Werte über `InsomniaApi`.

`check` führt auch `compat1206` aus: alle Hauptquellen gegen die lokalen Paper-1.20.6-Bibliotheken und Java 21. Standardpfad: `<home>/source/tools/smoke-1206/libraries`; andernorts mit `-Ppaper1206Libraries=/pfad/zu/libraries` überschreiben. Die Tests prüfen zusätzlich das Laden der ausgelieferten Klassen mit isolierten alten Bibliotheken. Neuere Dialog-APIs sind bereits in Cores Kompatibilitätsmodul gekapselt; Insomnia braucht dafür kein eigenes Zusatzmodul. `clean` erhält den laufenden Projektcache unter `build/gradle-cache`.

Speicher im Netzwerk, Client-Mod, Webseiten-Routen und Sonderfälle beim Sekundenschlaf stehen in [docs/TECHNICAL.md](docs/TECHNICAL.md).

Die verbleibende Traumzeit erscheint als Bossbar mit Minuten und Sekunden. Der Balken leert sich bis zum Aufwachen und warnt in der letzten Minute gelb, ab 30 Sekunden rot.

## Lizenz

VystormInsomnia ist kostenlos und steht unter der MIT-Lizenz, siehe [LICENSE](LICENSE).


### Server compatibility (0.25.0-26.2)

Bukkit chat and entity lookup, cross-version gamerule lookup and shared message/teleport adapters preserve sleep and dream workflows. Compatibility adapters target Spigot/CraftBukkit 26.2 and 26.3 while preserving the existing Paper paths. Bukkit means a Bukkit API server implementation such as CraftBukkit, not an additional server distribution. Install Vystorm Core 0.51.0 or newer.

Validation for this release: full automated build and tests pass, including the existing Paper 1.20.6 compatibility compilation. Both plugins enabled successfully on official Spigot 26.2 and 26.3 with Core 0.51.0; native gameplay checks exercised the Bukkit replacement paths.
Insomnia retains its development status: the existing two-client Purpur play-through is still required for validating every multiplayer sleep/dream interaction. The native harness and regression tests do not replace that manual acceptance check.
