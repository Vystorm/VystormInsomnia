## 0.31.1-26.2 — 2026-11-20

- Fixed: when `config.yml`, `stimulants.yml` or `mobs.yml` has a YAML mistake, the answer of `/insomnia reload` and the console now name the line and column of the mistake (before, only "while parsing a block mapping"). The file is never changed and the previous settings stay active; the settings menu no longer saves over a broken `config.yml`.
- Fixed: a `data/weekly-nightmare.yml` with a YAML mistake is copied to `weekly-nightmare.yml.broken-<date-time>` before the plugin writes it again.
- A dream command whose own switch is on but which depends on another switch now says which switch is missing: `/dream quest`, `/dream weekly`, `/dream invite` and `/dream join` answer "This needs “Dream Realm” (dream.enabled), which is switched off on this server." while `dream.enabled` is off. Seven languages, language key `feature_needs`.
- `/dream` with `fatigue.enabled` off: a player below the entry fatigue now hears that the dream needs the fatigue switch, instead of "You are not tired enough", which could never change while fatigue does not rise.
- README: the supported servers are written as tested: Paper and Purpur 26.2, Paper 1.20.6 and Spigot 26.2. An outdated English block from 0.25.0 at the end of the German README is gone.

## 0.31.0-26.2 — 2026-11-18

- Requires Vystorm Core 0.65.0. Update Core before you update this plugin.
- Every feature can now be switched on and off. All switches are listed in `/vadmin features` with a label and a one-line description in all seven languages. Changes apply at once; `web_page.enabled`, `tutorial.enabled`, `integrations.*` and the dream world layout apply at the next restart. New switches are added to your existing `config.yml` with their comments, and a missing switch counts as on, so nothing changes until you switch something off.
- New master switch `sleep.enabled`: off means beds behave as in vanilla (no sleep stages, no recovery from sleep, no sleep dialog, no time acceleration, no naps, no new rested bonus). The Dream Realm keeps working through `/dream`.
- New switches for sleep and fatigue: `sleep.quality.enabled`, `sleep.quality.cozy.enabled`, `activity.enabled`, and one switch per hallucination effect under `hallucinations.effects` (yawn, footsteps, creeper, door, darkness, blindness, shadow, sight loss).
- New switches for the dream world layout: `dream.world.sky_islands.enabled`, `dream.world.natural_accents.enabled`, `dream.world.natural_objects.enabled`, `dream.world.decorations.enabled`.
- New switches for menus and tools: `menu.enabled` (fatigue menu, `/insomnia`, `status`, `history`, `triggers`), `admin_tools.enabled` (`/insomnia admin ...`; `reload` always works), `client_hud.enabled` (bars and toasts of the Vystorm client mod), `web_page.enabled`, `tutorial.enabled`.
- Older keys keep their names and now also show in `/vadmin features`: `fatigue.exclude_creative`, `fatigue.exclude_spectator`, `sleep.cancel_vanilla_night_skip`, `sleep.wake_summary`, `sleep.dialog.day_naps`, `sleep.dialog.custom_time`, `sleep.acceleration.action_bar`, `player_menu.show_stage_to_players`, `integrations.cityclaim`, `integrations.bloodmoon`, `dream.world.dungeons_enabled`, `dream.world.ambient_light`. The six older `enabled` switches without a label (`dream`, `dream.quests`, `dream.companions`, `dream.weekly`, `fatigue.pause-afk`, `sleep.daytime`) now have one.
- A command of a switched-off feature answers "This feature is switched off on this server." It is also gone from tab completion and help while the switch is off. The fatigue menu entry disappears while `menu.enabled` is off.
- Placeholders: the plugin registers the namespace `sleep` with 10 values (fatigue, sleep debt, stage, sleeping, rested bonus, caffeine). Examples: `%vystorm_sleep_fatigue_int%` and `{sleep:stage_name}`. They work in Vystorm texts and, through PlaceholderAPI, in other plugins. A value of a switched-off feature is empty.
- Tab completion and help follow a switch at once, without registering the command again.
- README: the German README now names all seven languages, the GPL-3.0 licence and what the build proves about server support.

## 0.30.0-26.2 — 2026-11-17

- Requires Vystorm Core 0.64.0 or newer. Update Core before you update this plugin.
- The order in which Vystorm plugins react to the same event is now set in one place, Vystorm Core (`event-order` in Core's `config.yml`). The protection in the dream (no damage between players, the mob damage and explosion rules) now denies before other plugins act.
- Console messages about the configuration (update to a new version, a file that cannot be read) are now in English instead of German.
- License: GPL-3.0 from this version. Earlier versions stay under the MIT License.

## 0.29.1-26.2 — 2026-11-16

- Lighter on the server: the microsleep check (it runs for every online player) now looks at fatigue, cooldown and the next check time first and only reads a player's stored data when a microsleep could really start. Rested players cost almost nothing. Behaviour is unchanged.

## 0.29.0-26.2 — 2026-11-12

- Weekly nightmare challenge: each week (Monday 00:00 UTC) the Nightmare Keep gets rotating modifiers (swift, armoured or ferocious wardens, a bigger horde, darkness). Clear times form a weekly board, `/dream weekly` shows the modifiers, the time left and the five fastest clears, and the first clear of the week adds an extra reward to the dream pouch. Settings under `dream.weekly`, also in the first-run setup.

## 0.28.0-26.2 — 2026-11-11

- Shared dreams: while in the dream, `/dream invite <player>` asks another dreamer to join you (click to accept, or `/dream join`). Companions see your nightmare wardens, can fight them and are fought by them, and their kills count for your waves. Up to 3 companions (`dream.companions.max`); `/dream leave` or waking up ends it.

## 0.27.0-26.2 — 2026-11-10

- First-run setup: when the plugin is installed for the first time, admins answer a few questions or take the recommended settings, with a preview before anything is saved (needs Vystorm Core 0.54.0 or newer).
- All settings, including extra configuration files, appear in Core's new `/vadmin config` menu.

## 0.26.1-26.2 — 2026-11-08

- Fixed Rested hunger reduction and excluded sleepers incorrectly speeding up the night.
- Preserved earned Dream Pouch rewards when dream entry is disabled by a reload.
- Prevented stale player data after rapid reconnects and refused damaged fatigue records instead of resetting them.
- Rejected non-finite configuration values, applied CityClaim/BloodMoon toggles on reload, and cleared hallucination check data on logout.
- Enforced the documented Core 0.51.0 minimum and clarified configuration errors in all seven languages.

## 0.26.0-26.2 — 2026-11-07

- Added the shattered dream campaign. `/dream quest start` starts or resumes a saved, non-repeatable story across the existing Dream Realm dungeons. Complete the Crystal Vault to recover a lost guardian's memory, then choose `mercy` or `courage`. That choice sends you to the Cloud Ruins or Nightmare Keep. After completing it, choose `restore` or `master` and finish the remaining dungeon. Your two decisions lead to one of four permanent endings: Guardian of Dawn, Star Wanderer, Dream Liberator or Sovereign of Twilight.
- Fixed a brief owner-only mob spawn leak found by two-client packet acceptance: personal mobs and Dream Realm wave mobs are private before world tracking begins.
- Added all seven languages, persistent progress, permission checks and focused regression tests. Existing Spigot/CraftBukkit and Paper paths remain supported.
- See `FEATURE_VALIDATION.md` for the tested build and native server flows.

## 0.25.0-26.2 - Spigot / CraftBukkit compatibility

- Bukkit chat and entity lookup, cross-version gamerule lookup and shared message/teleport adapters preserve sleep and dream workflows.
- Requires Core 0.51.0 or newer.
- Daytime naps preserve and restore the original sleep-vote setting when players wake, teleport, sneak or disconnect.
- Existing configuration, player data and language files remain compatible.

## 0.24.0-26.2 (2026-11-06)

- Add complete French (`fr`), Brazilian Portuguese (`pt_br`), Italian (`it`) and Russian (`ru`) language bundles. Minecraft client locales and the Core language selector choose them automatically.
- Include translated tutorials in all four languages.
- Validate packaged language keys, placeholders, formatting and tutorial coverage in server-loader tests.

# Changelog

## 0.22.0-26.2

- Fixed: night-skip handling on Paper 1.20.6 (the skip reason type differs from 26.x; it is now read by name).
- Runs on Paper 1.20.6 – 26.x. Java 21 bytecode with `api-version: '1.20.6'`; newer servers still require their own Java version.
- Requires Vystorm Core 0.48.0 for portable dialogs and attribute lookup. On 1.20.6 menus and sleep choices use clickable chat because native dialogs do not exist. Configured materials/entities unavailable on a server are skipped with the existing diagnostics; all bundled gameplay content remains available.
- Uses shared gamerule constants and detects the actual daylight-wake call path instead of restricting it to version strings. Native dialogs remain available on newer servers.
- Adds offline `compat1206` to `check`, Java 21 bytecode/class-loading tests with isolated Paper 1.20.6 libraries, and native/chat fallback tests. Multiplayer runtime acceptance remains pending.

## 0.21.0-26.2

- New: sleep debt reminders. Awake players with at least 50% sleep debt receive a localized reminder to recover in a bed, even when coffee or campfire rest keeps fatigue low. The reminder repeats at most every 15 minutes per session; sleep, dream visits, microsleep, death and paused fatigue defer it. Players can disable reminders under `/vsettings` → Sleep, stored through Core PlayerPrefs. Server owners can configure `debt_reminder.enabled`, `threshold` and `interval_minutes` in config.yml or Core's config editor; existing configs receive missing defaults without replacing custom values. Uses `insomnia.use` and the existing master runtime task.

## 0.20.0-26.2

- New: caffeine forecast in `/insomnia status` and `/insomnia admin get <player>`. While a stimulant effect or crash is pending, shows remaining minutes/seconds, doses since full rest and fatigue after the crash based on current values. Includes suppressed fatigue and the accumulated crash, capped at 100%; activity and sleep can still change the outcome. Uses the existing permissions and Core language files (en/de/zh_cn), without additional configuration or runtime tasks.

## 0.19.0-26.2 (unreleased)

- New: wake-up summary. Getting out of bed before you are fully rested now tells you what the nap did: "You slept 14 s: fatigue 78% → 52%, sleep debt 30% → 22%. Sleep until you are fully rested for the full effect." Nothing is shown when less than one percent changed, after a full rest (that keeps its own message) or for dream-realm sleep. New config key `sleep.wake_summary` (default `true`, in the config editor).

## 0.18.0-26.2 (unreleased)

Needs Vystorm Core 0.45.0 for the commands.

- Simplified Chinese (zh_cn) translation
- `/insomnia`, `/dream`, `/wake` and `/dreamloot` now run through Vystorm Core's command system: subcommands, help, tab completion and error messages in the player's language (`/insomnia verlauf`, `/insomnia admin müdigkeit Steve +10`, `/insomnia 记录` work next to the English names for everyone), a clickable `/insomnia help` (`hilfe`, `帮助`), a "did you mean" for typos and entries in `/vhelp`. New localized root commands from the language files (`commands.*`): German `/schlaflos`, `/traumreise`, `/aufwachen`, `/traumbeute`, Chinese `/失眠`, `/入梦`, `/醒来`, `/梦境奖励`; which languages are active is set server-wide in Core (`commands.localized-aliases`). The English commands and all their arguments stay as they were. The commands are no longer declared in plugin.yml; with an older Core they are missing and the log says so.

- New: sleep dialog. When you lie down at night, a dialog asks what you want: sleep until morning (06:00), noon (12:00), evening (18:00) or the next night (22:00), until an own hour (hour picker 06:00 … 05:00), enter the dream realm (only shown while you may enter it: dream enabled, `insomnia.dream`, Survival, enough fatigue; cooldown, combat and region checks stay with `/dream`), or get up. Closing the dialog (ESC, "Keep sleeping") keeps your saved choice. Wake-up times always mean the coming day (the day that starts at the next 06:00). A checkbox "Don't ask again" makes the choice automatic. Day naps get a short dialog (keep napping, dream realm, get up) only while the dream realm is open to you, because the clock never runs faster for a nap. Bedrock players (Floodgate) and players who choose so get the same choices as clickable chat.
- New: personal settings in `/vsettings` → Sleep: ask with a dialog / use my saved choice without asking / ask in chat, the saved choice, and the own wake-up hour (Core `PlayerPrefs`).
- New: time acceleration instead of the instant night skip. The more players of a world sleep, the faster the clock runs: `speed = min_speed + (max_speed - min_speed) × (sleeping / players) ^ curve` (defaults 2, 10, 1.0: 1 of 4 sleeping = 4×, 2 of 4 = 6×, everyone = 10×, a night then takes about a minute, roughly one full Insomnia sleep cycle). Spectators and players ignored for sleeping are not counted. Everyone in the world sees "2/4 sleeping – time runs 6× faster" in the action bar. The clock runs towards the earliest wake-up time of all sleepers; each sleeper wakes at their own time ("You wake up. It is 06:00") and the next earliest takes over. Vanilla's daylight wake is held back for sleepers whose time is later (same 26.2/26.3 check as day naps). Rain and thunder clear when the morning passes, as with the vanilla skip. Vanilla's skip is stopped by cancelling its night-skip event; no gamerule is changed (raising `players_sleeping_percentage` above 100 would show every sleeper "No amount of rest can pass this night"), so nothing needs restoring after a reload or crash. Worlds whose `players_sleeping_percentage` is above 100, worlds with `advance_time` off, the dream world and non-overworld worlds are left alone. One small timer per world runs only while someone there sleeps; it also follows other plugins or `/time` setting the clock back.
- New config keys (all with defaults, in the config editor): `sleep.dialog.enabled`, `sleep.dialog.day_naps`, `sleep.dialog.presets.morning|noon|evening|night` (clock time hh:mm, `''` hides one), `sleep.dialog.custom_time`, `sleep.acceleration.enabled`, `min_speed`, `max_speed`, `curve`, `worlds` (empty = all), `action_bar`. Existing config files use the defaults without being rewritten. New language keys `sleep_choice.*` and `setting.sleep.dialog.*` / `setting.sleep.acceleration.*` (en/de). No new permission, no storage change.
- With the sleep dialog on, the old chat hint "Rest here, or choose /dream" is no longer sent (the dialog shows the same choice).
- Fixed: on Minecraft 26.3 a daytime nap ended at once, because the check that keeps napping players in bed against the vanilla daylight wake only accepted 26.2. The wake call path is unchanged in 26.3 (checked against the Paper/Purpur 26.3 server jars), so 26.2 and 26.3 (and their patch releases) are now accepted; unknown versions still fail open and let the player wake.

## 0.17.0-26.2 (2026-10-30)

- New: resting by a campfire. Out in the wild without a bed, players stand still for a few seconds next to a lit campfire or soul campfire and their fatigue slowly drops (0.2 per second), but never below 30 %. Moving, combat, sleeping or Creative/Spectator end the rest; the action bar shows the progress, `/insomnia status` and `/insomnia history` show it too. Sleep debt and the rested bonus still need a real bed. Runs in the existing runtime tick, the block search only happens for players who already stand still. Config: `rest.campfire.enabled`, `radius`, `settle_seconds`, `fatigue_per_second`, `min_fatigue` (on by default, also in the server settings and the config editor).
- New: sleep overview for admins. `/insomnia admin overview` or the new "All players" button in the fatigue menu lists every online player, most tired first, with stage, fatigue, sleep debt and whether they are asleep or resting. Click a player for their values and quick actions: fully rest, clear sleep debt, fatigue −20 / +20. The console gets the list as text. With Vystorm Core 0.43.0+ the same player dialog also opens from Core's player menu (sneak + right-click a player); other players see there only how tired someone looks (stage, no numbers). Config: `player_menu.enabled`, `player_menu.show_stage_to_players` (both on). No new permission: the overview needs `insomnia.admin`.
- Loot now uses Vystorm Core weighted loot tables (Core 0.44.0+): the drops of each nightmare mob tier in `mobs.yml` become the own table `vystorminsomnia:mobs/tier_<id>` (mode each, same chances and amounts) and are rolled through `category:mob_drop_common`. New per-tier key `tiers.<id>.loot_table` (Core table or category id, empty = the category). Drops stay owner-only, the ECHO_SHARD window limit still applies; amounts above 64 are capped to one stack. Older Cores keep the built-in roll. Dream dungeon rewards are fixed lists and stay as they are.
- Nightmare mob drops follow the Vystorm Core mob loot rule (Core 0.44.0+, capability `mob-loot`): what drops is the tier's own list, how good only the tier's loot level (`tiers.<id>.loot_level` in `mobs.yml`, default 15 per tier id: 15/30/45; Core `loot.mob-level-luck`). The owner's luck (Luck attribute, item or pet bonuses) no longer changes them. Each drop's rarity now follows its chance, so at level 0 the odds are exactly the configured ones and higher levels make the rarer drops likelier. Nightmare mobs are claimed for Insomnia (`MobLoot.claim`), so SpawnDirector and VystormItems never roll them a second time; VystormItems monster drops are added to the Insomnia roll as owner-only drops.
- No data or storage changes; existing config files use the defaults of the new keys without being rewritten.

Fixed (before release):

- The "fatigue -20 / +20" buttons in the admin player dialog moved the shown fatigue by the stimulant suppression as well (a player under a stimulant could jump by 40 or not change at all). They now change the shown value by exactly 20.
- Campfire rest skipped about every other second when the server timer fired a few milliseconds early, so the start and the action bar lagged behind.
- `/insomnia admin setfatigue <player> <value|+n|-n>` had the same stimulant miscalculation as the dialog buttons: under a stimulant the shown fatigue landed off by the suppression (`+10` could even lower it). The value now sets or shifts the shown fatigue exactly, as the command reply says.

## 0.16.0-26.2 (2026-10-29)

- New: `/insomnia status` now shows the next fatigue stage and roughly how long until you reach it at normal play (for example "Next stage: Exhausted in about 1 h 20 min"). The estimate uses `fatigue.base_per_minute`; sprinting, mining and combat make it come sooner. It is hidden while fatigue is paused, while sleeping and in the last stage. `/insomnia admin get` shows it too.
- `/insomnia admin setfatigue|setdebt` accept relative values: `+10` adds 10, `-5` removes 5 from the current value (still clamped to 0-100). Plain numbers set the value as before; a signed negative such as `-5` used to set 0 and now subtracts 5.
- New language keys `status_next.minutes` and `status_next.hours` (en/de). No config or permission changes.

## 0.15.1-26.2 (2026-10-28)

- Registers itself for the Vystorm Core update check (Core 0.40.2+); older Cores are unaffected.

## 0.15.0-26.2 (2026-10-28)

- New: cozy bedrooms. Furnishings within 4 blocks of the bed make you sleep better: carpet or wool, a potted plant, a bookshelf, a lantern or candle, a painting or item frame and a chest or barrel. Each kind counts once and adds 4 % sleep quality (up to +24 %), on top of roof, darkness and city. When you lie down you see your coziness score, what you already have and what is still missing; `/insomnia status` shows the same while you stand next to a bed. Tune or switch off with `sleep.quality.cozy_per_furnishing` (0 disables) and `sleep.quality.cozy_radius`.
- The general `/insomnia admin` usage hint now comes from the language files (`admin_usage`) instead of a hard-coded English line.

## 0.14.1-26.2 — Klarere Admin-Befehle und MIT-Lizenz

- Incomplete admin commands now show the matching usage line (for example `/insomnia admin setfatigue <player> <0-100>`) instead of "player not found"; unknown admin subcommands show the subcommand list.
- `setfatigue`/`setdebt` reject `NaN` and `Infinity`; stored fatigue and sleep debt can no longer become NaN (an already corrupted value reads as 0).
- `/dream`, `/wake` and `/dreamloot` no longer suggest player names on tab, since they take no arguments.
- Released under the MIT License (`LICENSE`). No config, permission or data changes.

## 0.14.0-26.2 — Drei prozedurale Dungeons

- Added variable Cloud Ruins courtyards, Crystal Vault chambers and Nightmare Keep arenas, surveyed on supported lower terrain with walkable approaches from arrival. No schematics or fixed world positions.
- Restored personal waypoint exploration, ordered crystal interactions and warden waves using stable per-visit reward receipts. Wrong crystal order resets progress; uncredited mob deaths leave a replacement slot.
- Added localized entrance directions and encounter instructions; `/dream` repeats directions during a visit. Existing inventory isolation and Dream Pouch delivery remain in Core.
- `dream.world.dungeons_enabled` enables all three sites (default true); layout version 17 rebuilds the disposable world on restart. No mirrored world or towers.

## 0.13.0-26.2 — Senken und natürlicher Unterwuchs

- Added shallow winding depressions to the continuous height field, blending smoothly through climate boundaries.
- Added coherent biome-specific ground patches, ferns, low azaleas and flowers on both landscape layers, plus exposed rocky slope patches. Existing objects and trees remain intact.
- Ground dressing follows `dream.world.natural_accent_density`; 0 disables it. Layout version 16 rebuilds the managed dream world on restart. No new runtime tasks or player data changes.

## 0.12.0-26.2 — Prozedurale Naturobjekte

- Added an independent procedural object generator for variable fallen wood, weathered boulders, mushroom groups and mineral formations on both terrain layers.
- Object placement validates the whole shape before writing, preserves existing solid terrain/trees and keeps arrival clear. Seed controls position, orientation and dimensions; no schematics or fixed landmark positions.
- Added `dream.world.natural_object_density` (0–100, default 65). Layout version 15 includes this control; geometry changes require restart.

## 0.11.0-26.2 — Prozedurale Himmelsinseln und Biomdetails

- Added a second continuous 3D density field for floating upper island groups, with variable relief, tapered undersides and interior openings. No fixed island centers or templates.
- Seeded biome-specific flower/mushroom patches, mineral ridges, hanging roots and upper-island trees follow generated surfaces.
- Independent 0–100 configuration for sky island coverage and natural accents; layout version 13 includes both controls in the rebuild hash. Lower terrain, arrival and dream-time bossbar remain intact.

## 0.10.1-26.2 — Traumzeit als Bossbar

- Show remaining dream time as a localized m:ss bossbar, draining against the existing visit deadline. Yellow/red warnings near expiry; removed on every visit cleanup. Replaces repeated five-second chat updates without adding scheduler tasks.

## 0.10.0-26.2 — Hügel, Rücken und Täler

- Added broad multiscale hills, rolling valleys and rounded ridges. Climate weights blend meadow, forest, crystal, garden and night relief continuously across biome borders.
- Increased vertical headroom to y191; natural terrain stays below y164, leaving space for tree canopies without clipped peaks.
- Exposed rock on steep slopes, short tree roots on uneven ground and mineral clusters anchored to their local hillside. Decoration no longer overwrites natural slopes.
- Preserved the single natural landscape without mirrored terrain or gameplay structures. Layout version 12 rebuilds only the managed disposable dream world.

## 0.9.0-26.2 — Natürliche Biomlandschaft

- Replaced preformed island bodies with a full seeded 3D density field: natural land topology, overhangs and interior openings.
- Removed the mirrored ceiling, offset world, towers, dungeon buildings, ruins and artificial routes from the active layout. Landscape comes first; gameplay structures are deferred.
- Five coherent climate-based biome regions with matching Minecraft grass/foliage biomes, clustered vegetation and sparse low mineral outcrops. Spawn uses naturally supported terrain with a small clear arrival area.
- Core visit/return safety and existing reward-pouch contents remain intact. No new dungeon rewards in the landscape-only layout.
- Layout version 11 rebuilds only the managed disposable dream world.

## 0.8.0-26.2 — Seedbasierte Inselanordnung

- Replaced fixed island and landmark coordinates with a deterministic spatial generator shared by terrain, paths, architecture and interaction metadata.
- Staggered island elevations and curved rising routes connect nearby groups; satellites vary in size and placement. Spawn remains stable, tower counterparts retain matching safe arrival heights.
- Dungeon foundations have clipped corners instead of full square slabs; bridges use thin decks instead of deep repetitive stone fins. Dungeon room templates remain authored.
- Layout version 7 regenerates only the Core-managed disposable dream world on restart.

## 0.7.0-26.2 — Gespiegelte Inselwelt

- Replaced the small flat arena with bounded, seed-varied floating island groups, five biome palettes, branching trees, curved paths and distinct authored dungeon architecture.
- Added two normal-gravity representations of the same world, each with vertically mirrored inert scenery overhead.
- Added three paired button towers; shared dungeon IDs preserve progress and prevent duplicate reward claims across sides. Transfers retain the visit deadline and are rejected during encounters, combat, recovery or unsafe arrival.
- Six plugin-owned arrival chunk tickets prevent destination chunks unloading during transfer; all tickets are released on shutdown/failure.
- Added configurable decoration density, static ambient lighting and tower timing/effects. Geometry settings take effect on restart through the managed-world layout hash (layout version 5).
- This is a bounded first world-generation implementation. Random room graphs, side-dependent puzzle variants and flowing waterfalls remain future content.

## 0.6.0-26.2 — Traumweite

- Added daytime bed rest and explicit `/dream` entry, `/wake` return, and `/dreamloot` reward claims.
- Added a Core-managed finite dream world with Cloud Ruins waypoints, ordered Crystal Vault interactions, and bounded owner-targeted Nightmare Keep waves.
- Dream rewards are credited durably and idempotently to Core RewardPouch before a dungeon is marked complete; full inventories retain pending rewards.
- Dream duration, entry threshold/world allowlist, warnings, rewards, world identity, and encounter waves are configurable.
- Dream recovery uses elapsed online sleep time and does not grant the full-rest reset or rested bonus.
- Requires Vystorm Core 0.32.0 (`managed-worlds`, `reward-pouch`).
- Verified the new dream flow with two real clients on local Purpur 26.2, including dungeon completion, daytime sleep, owner isolation, reward delivery, timeout, and forced-crash recovery. The broader baseline/optional-integration release matrix remains incomplete; no live deployment.
