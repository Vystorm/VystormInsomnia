# Vystorm Insomnia

Staying awake makes each player tired. Real sleep reduces fatigue and sleep debt; ignoring them brings hallucinations, brief microsleeps and personal hostile encounters. Rested players receive a small XP, hunger and regeneration bonus. The Dream Realm turns a rest stop into a bounded exploration trip with puzzles, combat and safely stored loot.

## Shared dreams (0.28.0)

In the dream, `/dream invite <player>` asks another dreamer to join you. Companions see and fight your Nightmare Keep wardens together with you; their kills count for your waves. Up to 3 companions; `/dream leave` or waking up ends it.

## The Shattered Dream campaign (0.26.0)

`/dream quest start` starts or resumes a saved, non-repeatable story across the existing Dream Realm dungeons. Complete the Crystal Vault to recover a lost guardian's memory, then choose `mercy` or `courage`. That choice sends you to the Cloud Ruins or Nightmare Keep. After completing it, choose `restore` or `master` and finish the remaining dungeon. Your two decisions lead to one of four permanent endings: Guardian of Dawn, Star Wanderer, Dream Liberator or Sovereign of Twilight.

`/dream quest status` displays the current chapter, required dungeon or ending. `/dream quest pause` pauses progression without erasing it; `start` resumes it. Ordinary dungeon exploration and loot still work while the campaign is paused. Only actual completed puzzles, waypoint routes and owned combat waves advance the corresponding active chapter. Choices cannot skip chapters or rewrite a finished ending.

`insomnia.dream.quest` defaults to true. `dream.quests.enabled` controls the campaign; `dream.quests.rewards.<ending>` defines each ending's item rewards. Player progress is stored through Core VersionedStore in `data/dream-quests.json`, independently of temporary dream visits. Waking, reconnecting and restarting retain chapters and choices. The completed state is saved before its one-time Core RewardPouch credit; its permanent per-player receipt prevents repeated status commands or restarts from awarding it again. Use `/dreamloot` outside the dream to claim pending entries, one per use. The campaign reuses the existing dream world, visit snapshots, reward pouch and master runtime task. Story text, choices and help are available in all seven languages.

## Existing gameplay

- **Fatigue and sleep debt:** separate values from 0–100, with five stages at 0/20/40/60/80. Sprinting, swimming, mining and combat increase the active fatigue rate. The highest active multiplier applies, rather than multiplying all activities together.
- **Actual-time sleep:** falling asleep, light sleep and deep sleep normally take about a minute. A partial rest reduces fatigue and debt; full deep sleep clears them and grants Rested. Roofs, lighting, nearby monsters, weather and furnishings influence sleep quality.
- **Bedrooms and campfire rest:** six furnishing categories contribute comfort. Campfires provide bounded emergency fatigue relief after settling nearby; they do not repay sleep debt or grant Rested.
- **Sleep choices and acceleration:** choose a wake-up time, enter a dream or get up through a dialog or clickable chat. Night speed follows the proportion of sleepers; individual wake times, disabled worlds and gamerules remain respected. Bedrock/Floodgate and older servers receive the chat fallback.
- **Stimulants:** configurable coffee and energy drinks temporarily suppress visible fatigue. Repeated use increases tolerance and the later crash; underlying sleep debt remains. Status shows remaining duration and the expected crash.
- **Personal hallucinations and microsleeps:** sounds, darkness and shadows affect the tired player. During a microsleep a protected equipment mirror remains as an ArmorStand body; damage wakes and hurts its owner. Game mode, flight and visibility are restored on exit or recovery.
- **Personal mobs and loot:** three fatigue-dependent tiers, owner-only targeting, damage and drop pickup, cleanup on sleep/disconnect and a bounded Echo Shard reward window. Other players cannot farm someone else's encounter.
- **Dream Realm:** seeded floating terrain with five biomes, upper islands, vegetation, rocks, trails and three dungeons. Cloud Ruins use waypoints, Crystal Vault uses a sequence puzzle, and Nightmare Keep uses owned enemy waves. The world is finite and deliberately separate from WorldForge terrain generation.
- **Safe dream visits:** Core persists the player's entry snapshot, isolates the visit inventory and returns the player through `/wake`. Dungeon loot enters the existing durable reward pouch. Dreams provide recovery for actual time spent, without a full Rested bonus.
- **Menus and history:** personal status, up to 24 session history entries in pages, trigger explanations, a player-menu entry, a HUD and an administrator overview with targeted actions. Persistent personal settings control sleep prompts and sleep-debt reminders.

## Installation

Install Vystorm Core **0.64.0 or newer** and this JAR in `plugins/`. Use **Java 25**, the runtime used for acceptance. Spigot/CraftBukkit 26.2–26.3 are supported alongside the existing Paper/Purpur compatibility paths; common source and class-loading checks also cover Paper 1.20.6. The plugin emits Java 21 bytecode, but that alone is not a Java 21 runtime acceptance claim.

The first start creates `config.yml`, `stimulants.yml`, `mobs.yml` and language files. English, German, Simplified Chinese, French, Brazilian Portuguese, Italian and Russian follow Core's player language settings. Native dialogs and other modern Paper functionality remain in use where available; guarded Bukkit/chat implementations serve the other supported runtimes. Materials and entity types absent from an older server are diagnosed.

VystormCityClaim and BloodMoon are optional. City/rental sleep bonuses use CityClaim's regions published through Core. BloodMoon modifiers use its public registered service. Neither integration is a hard dependency.

## Commands and permissions

| Command | Purpose |
|---|---|
| `/insomnia [menu]` | Open the personal fatigue menu |
| `/insomnia status` | Stages, estimated progression, stimulant forecast and sleep quality |
| `/insomnia history [page]`, `/insomnia triggers [page]` | Read recent session history and hallucination explanations |
| `/insomnia admin overview` | Inspect online players and targeted administrator actions |
| `/insomnia admin get <player>` | Inspect a player's values |
| `/insomnia admin setfatigue|setdebt <player> <value>` | Set 0–100 or use signed changes such as `+10` |
| `/insomnia admin sleep <player>` | Fully rest a player |
| `/insomnia admin effect <player> <effect>` | Trigger a named hallucination |
| `/insomnia admin spawnmob <player> <tier>` | Spawn a personal encounter |
| `/insomnia admin reload` | Validate and reload runtime configuration and languages |
| `/dream`, `/wake`, `/dreamloot` | Enter near a safe bed, return safely, claim one pouch entry |
| `/dream quest <action>` | Saved story status, start, pause or current choice |
| `/vsettings` → Sleep | Personal sleep prompts, wake time and debt reminders |

`insomnia.use`, `insomnia.dream` and `insomnia.dream.quest` default to true. `insomnia.admin` and `insomnia.*` default to operators. World, fatigue, combat and safe-bed checks still apply to dream entry. Campaign permission does not bypass these checks.

## Configuration and storage

Use `/vadmin config VystormInsomnia` or edit the three YAML configuration files. Invalid reloads retain the previous valid configuration. Major sections are `fatigue`, `activity`, `sleep`, `dream`, `hallucinations`, `microsleep`, `insomnia_mobs`, `rested`, `rest.campfire`, `debt_reminder` and `player_menu`. Dream generation/seed changes require a restart. Storage and integration switches also require a restart.

File-backed fatigue uses Core VersionedStore in `data/fatigue.yml`. Optional MySQL/MariaDB fatigue persistence uses Core's shared database settings, versioned migrations, server claims and one-time file import. Dream visits, quest progress and reward pouches use their own local Core-backed stores; the campaign is **per server**, not a network-wide SQL quest system. Do not copy an active server's player snapshots or reward ledgers between servers.

The pre-0.5 `data/players.yml` and `messages.yml` formats are not imported. Custom messages belong under the matching keys in `lang/<language>.yml`. Core restores missing default stimulant/mob entries; edit an unwanted entry instead of only deleting it.

## API and validation

Developers can obtain `InsomniaApi` from Bukkit's services manager and observe the public fatigue/sleep events. Server access remains on the main thread; the existing master task drives internally rate-limited systems. Optional services stay behind their adapters.

See the [changelog](CHANGELOG.md). The earlier German overview is retained in [README.de.md](README.de.md).

## License

Copyright (c) 2026 Esoren

Versions after 0.29.1 are licensed under the GNU General Public License v3.0 (GPL-3.0), see [LICENSE](LICENSE).
Version 0.29.1 and earlier were released under the MIT License ([LICENSE-MIT-until-0.29.1.txt](LICENSE-MIT-until-0.29.1.txt)) and stay available under those terms.

