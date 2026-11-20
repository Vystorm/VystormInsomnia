package at.vystorm.insomnia;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.web.ClientServices;
import Vystorm.vystorm_core.web.ClientUi;
import Vystorm.vystorm_core.web.ClientUiInterface;
import at.vystorm.insomnia.command.InsomniaCommand;
import at.vystorm.insomnia.command.InsomniaCommands;
import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.model.PlayerFatigueState;
import at.vystorm.insomnia.service.*;
import at.vystorm.insomnia.ui.FatigueHud;
import at.vystorm.insomnia.ui.Messages;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The 0.31.0 feature switches: off stops the feature, on behaves as before, an old config keeps working. */
class InsomniaSwitchesTest {
    /** Every switch that is new in 0.31.0, as written in the shipped config.yml. */
    static final List<String> NEW = List.of("sleep.enabled", "sleep.quality.enabled", "sleep.quality.cozy.enabled",
            "activity.enabled", "menu.enabled", "admin_tools.enabled", "client_hud.enabled", "web_page.enabled",
            "tutorial.enabled", "dream.world.sky_islands.enabled", "dream.world.natural_accents.enabled",
            "dream.world.natural_objects.enabled", "dream.world.decorations.enabled",
            "hallucinations.effects.yawn", "hallucinations.effects.footsteps", "hallucinations.effects.creeper",
            "hallucinations.effects.door", "hallucinations.effects.darkness", "hallucinations.effects.blindness",
            "hallucinations.effects.shadow", "hallucinations.effects.sight_loss");
    static final List<String> LANGUAGES = List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn");

    @TempDir Path folder;
    private JavaPlugin plugin;

    private static InputStream resource(String name) {
        return Objects.requireNonNull(InsomniaSwitchesTest.class.getClassLoader().getResourceAsStream(name), name);
    }

    private static YamlConfiguration yaml(String name) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(resource(name), StandardCharsets.UTF_8));
    }

    @BeforeEach
    void setUp() throws IOException {
        for (String name : List.of("config.yml", "stimulants.yml", "mobs.yml"))
            Files.copy(resource(name), folder.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getResource("config.yml")).thenAnswer(ignored -> resource("config.yml"));
    }

    /** A real config whose config.yml on disk is the shipped one with these switches set. */
    private InsomniaConfig with(Object... pathValue) throws IOException {
        YamlConfiguration file = yaml("config.yml");
        for (int i = 0; i < pathValue.length; i += 2) file.set((String) pathValue[i], pathValue[i + 1]);
        file.save(folder.resolve("config.yml").toFile());
        return new InsomniaConfig(plugin);
    }

    private static List<String> switches() {
        List<String> all = new ArrayList<>(InsomniaConfigEditor.DECLARED);
        for (var entry : InsomniaConfigEditor.ENTRIES) if (entry.path().endsWith(".enabled") && !all.contains(entry.path())) all.add(entry.path());
        return all;
    }

    // ---- shipped file, old files, descriptions -------------------------------------------------------------------

    @Test void givenShippedConfig_whenRead_thenEverySwitchIsABooleanAndOn() {
        YamlConfiguration config = yaml("config.yml");
        for (String path : switches()) assertTrue(config.isBoolean(path), path);
        for (String path : NEW) assertTrue(config.getBoolean(path), path);
        assertTrue(switches().containsAll(NEW), "every new switch has a description");
    }

    @Test void givenEverySwitch_whenDescribed_thenAllSevenLanguagesHaveLabelAndDescription() {
        for (String code : LANGUAGES) {
            YamlConfiguration lang = yaml("lang/" + code + ".yml");
            for (String path : switches()) {
                assertFalse(lang.getString("setting." + path + ".label", "").isBlank(), code + " label " + path);
                assertFalse(lang.getString("setting." + path + ".desc", "").isBlank(), code + " desc " + path);
            }
            assertFalse(lang.getString(InsomniaCommand.OFF, "").isBlank(), code + " off message");
            for (String key : InsomniaPlaceholders.KEYS) assertFalse(lang.getString("placeholder." + key, "").isBlank(), code + " " + key);
        }
    }

    /**
     * 0.31.1: every boolean that Core's {@code /vadmin features} lists for config.yml (keys named {@code enabled},
     * top-level booleans, declared switches) has a readable name and a description in all seven languages, so the
     * screen never shows a raw key path.
     */
    @Test void givenEveryFeatureCoreListsFromConfigYml_whenDescribed_thenAllSevenLanguagesHaveLabelAndDescription() {
        YamlConfiguration config = yaml("config.yml");
        List<String> listed = new ArrayList<>();
        java.util.Set<String> housekeeping = java.util.Set.of("metrics", "debug", "verbose", "sound", "console-log", "placeholderapi", "update-check");
        for (String path : config.getKeys(true)) {
            if (!config.isBoolean(path)) continue;
            boolean feature = path.equals("enabled") || path.endsWith(".enabled") || (path.indexOf('.') < 0 && !housekeeping.contains(path));
            if (feature || InsomniaConfigEditor.DECLARED.contains(path)) listed.add(path);
        }
        assertTrue(listed.size() >= 30, "found " + listed);
        for (String code : LANGUAGES) {
            YamlConfiguration lang = yaml("lang/" + code + ".yml");
            for (String path : listed) {
                assertFalse(lang.getString("setting." + path + ".label", "").isBlank(), code + " label " + path);
                assertFalse(lang.getString("setting." + path + ".desc", "").isBlank(), code + " desc " + path);
            }
            for (String path : listed)
                assertTrue(InsomniaConfigEditor.DECLARED.contains(path) || InsomniaConfigEditor.ENTRIES.stream().anyMatch(e -> e.path().equals(path)),
                        "registered with a label: " + path);
        }
    }

    @Test void givenADependentFeature_whenItsBaseSwitchIsOff_thenEveryLanguageNamesTheSwitch() throws IOException {
        for (String code : LANGUAGES) {
            String text = yaml("lang/" + code + ".yml").getString(InsomniaConfig.NEEDS, "");
            assertTrue(text.contains("{switch}") && text.contains("{path}"), code + " " + text);
        }
        InsomniaConfig config = with();
        Messages texts = mock(Messages.class);
        when(texts.plain(any(), org.mockito.ArgumentMatchers.eq("setting.dream.enabled.label"))).thenReturn("Dream Realm");
        when(texts.chat(any(), anyString(), any(Object[].class))).thenAnswer(i -> {
            Object[] all = i.getArguments();
            StringBuilder out = new StringBuilder((String) all[1]);
            for (int k = 2; k < all.length; k++) out.append('|').append(all[k]);
            return out.toString();
        });
        config.bindMessages(texts);
        assertEquals(InsomniaConfig.NEEDS + "|switch|Dream Realm|path|dream.enabled", config.needs(mock(Player.class), "dream.enabled"));
    }

    @Test void givenOldConfigWithoutNewKeys_whenLoaded_thenBehavesAsBefore() throws IOException {
        YamlConfiguration old = yaml("config.yml");
        for (String path : NEW) old.set(path, null);
        for (String top : List.of("menu", "admin_tools", "client_hud", "web_page", "tutorial")) old.set(top, null);
        old.save(folder.resolve("config.yml").toFile());
        assertFalse(Files.readString(folder.resolve("config.yml")).contains("admin_tools"));
        InsomniaConfig config = new InsomniaConfig(plugin);
        assertTrue(config.sleepEnabled() && config.sleepQualityEnabled() && config.cozyEnabled() && config.activityEnabled());
        assertTrue(config.menuEnabled() && config.adminToolsEnabled() && config.clientHudEnabled() && config.webPageEnabled() && config.tutorialEnabled());
        assertEquals(0.04, config.cozyPerFurnishing());
        assertEquals(24, config.hallucinationWeight("yawn", 1));
        assertEquals(1.2, config.activity("sprint", 0));
        assertEquals(100, config.dreamSkyIslandDensity());
        assertEquals(65, config.dreamNaturalAccentDensity());
        // Core's config editor merges the missing keys into the file when it registers config.yml (mergeDefaults).
    }

    // ---- sleep ---------------------------------------------------------------------------------------------------

    @Test void givenSleepOff_whenRead_thenDialogAccelerationNapsAndNightSkipFollow() throws IOException {
        InsomniaConfig off = with("sleep.enabled", false, "sleep.cancel_vanilla_night_skip", true);
        assertFalse(off.sleepEnabled());
        assertFalse(off.sleepDialogEnabled());
        assertFalse(off.accelerationEnabled());
        assertFalse(off.daytimeBedSleepEnabled());
        assertFalse(off.cancelVanillaNightSkip(), "vanilla beds: no plugin night-skip rule");
        assertFalse(off.wakeSummary());
        InsomniaConfig on = with("sleep.enabled", true, "sleep.cancel_vanilla_night_skip", true);
        assertTrue(on.sleepDialogEnabled() && on.accelerationEnabled() && on.daytimeBedSleepEnabled() && on.cancelVanillaNightSkip());
    }

    @Test void givenSleepOff_whenPlayerLiesDown_thenNoSessionAndARunningOneEnds() {
        InsomniaConfig config = SwitchDefaults.on(mock(InsomniaConfig.class));
        when(config.cozyEnabled()).thenReturn(false);
        var fatigue = mock(FatigueService.class);
        var player = mock(Player.class);
        UUID id = UUID.randomUUID();
        var state = new PlayerFatigueState(id, 1000);
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        when(player.isSleeping()).thenReturn(true);
        when(fatigue.ready(id)).thenReturn(true);
        when(fatigue.require(id)).thenReturn(state);
        when(fatigue.state(id)).thenReturn(Optional.of(state));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            SleepService sleep = new SleepService(config, fatigue, mock(PersistenceService.class), mock(SleepQualityService.class),
                    mock(RestedBonusService.class), mock(StimulantService.class), mock(InsomniaMobService.class));
            when(config.sleepEnabled()).thenReturn(false);
            sleep.start(player, 1000);
            assertFalse(sleep.isSleeping(id), "off: beds are vanilla");
            when(config.sleepEnabled()).thenReturn(true);
            sleep.start(player, 1000);
            assertTrue(sleep.isSleeping(id), "on: sleep starts as before");
            when(config.sleepEnabled()).thenReturn(false);
            sleep.tick(2000);
            assertFalse(sleep.isSleeping(id), "switched off at runtime: the bed session ends");
        }
    }

    @Test void givenSleepQualityOff_whenCalculated_thenBaseQualityAndNothingIsChecked() {
        InsomniaConfig config = SwitchDefaults.on(mock(InsomniaConfig.class));
        when(config.sleepQuality(anyString(), org.mockito.ArgumentMatchers.anyDouble())).thenAnswer(i -> i.getArgument(1));
        when(config.sleepQualityEnabled()).thenReturn(false);
        World world = mock(World.class);
        Location at = mock(Location.class);
        when(at.getWorld()).thenReturn(world);
        Player player = mock(Player.class);
        when(player.getLocation()).thenReturn(at);
        var service = new SleepQualityService(config, mock(at.vystorm.insomnia.integration.CityClaimIntegration.class),
                mock(at.vystorm.insomnia.integration.BloodMoonIntegration.class));
        assertEquals(1.0, service.calculate(player));
        verify(world, never()).getHighestBlockYAt(any(Location.class));
    }

    @Test void givenCozyOff_whenRead_thenFurnishingsAddNothing() throws IOException {
        assertEquals(0.04, with().cozyPerFurnishing());
        InsomniaConfig off = with("sleep.quality.cozy.enabled", false);
        assertFalse(off.cozyEnabled());
        assertEquals(0.0, off.cozyPerFurnishing());
        assertFalse(with("sleep.quality.enabled", false).cozyEnabled(), "coziness is part of sleep quality");
    }

    // ---- fatigue -------------------------------------------------------------------------------------------------

    @Test void givenActivityOff_whenRead_thenEveryActivityCountsAsNormal() throws IOException {
        InsomniaConfig off = with("activity.enabled", false);
        for (String key : List.of("sprint", "swimming", "mining", "combat", "boss")) assertEquals(1.0, off.activity(key, 9), key);
        assertEquals(2.0, with().activity("boss", 9));
    }

    @Test void givenOneHallucinationOff_whenRead_thenItsWeightIsZeroAndTheOthersStay() throws IOException {
        InsomniaConfig off = with("hallucinations.effects.creeper", false);
        assertEquals(0, off.hallucinationWeight("creeper", 1));
        assertEquals(24, off.hallucinationWeight("yawn", 1));
        assertEquals(12, with().hallucinationWeight("creeper", 1));
    }

    // ---- dream world ---------------------------------------------------------------------------------------------

    @Test void givenDreamLayersOff_whenRead_thenDensityZero() throws IOException {
        InsomniaConfig off = with("dream.world.sky_islands.enabled", false, "dream.world.natural_accents.enabled", false,
                "dream.world.natural_objects.enabled", false, "dream.world.decorations.enabled", false);
        assertEquals(0, off.dreamSkyIslandDensity());
        assertEquals(0, off.dreamNaturalAccentDensity());
        assertEquals(0, off.dreamNaturalObjectDensity());
        assertEquals(0, off.dreamDecorationDensity());
        InsomniaConfig on = with();
        assertEquals(100, on.dreamSkyIslandDensity());
        assertEquals(65, on.dreamNaturalObjectDensity());
        assertEquals(100, on.dreamDecorationDensity());
        assertTrue(InsomniaConfigEditor.RESTART.contains("dream.world"));
    }

    // ---- menu, admin tools, client, web, tutorial ----------------------------------------------------------------

    private static CommandSender sender(Player player) {
        when(player.hasPermission(anyString())).thenReturn(true);
        return player;
    }

    @Test void givenMenuOff_whenStatusOrHistory_thenOffMessage() {
        InsomniaConfig config = SwitchDefaults.on(mock(InsomniaConfig.class));
        when(config.menuEnabled()).thenReturn(false);
        when(config.message(any(), anyString())).thenAnswer(i -> i.getArgument(1));
        Player player = mock(Player.class);
        var command = new InsomniaCommand(config, mock(FatigueService.class), null, null, null, null, () -> {}, null);
        for (String sub : List.of("status", "history", "triggers", "menu")) command.execute(sender(player), "insomnia", sub);
        command.execute(player, "insomnia");
        verify(player, times(5)).sendMessage("feature_off");
    }

    @Test void givenAdminToolsOff_whenAdminCommand_thenOffMessageButReloadWorks() {
        InsomniaConfig config = SwitchDefaults.on(mock(InsomniaConfig.class));
        when(config.adminToolsEnabled()).thenReturn(false);
        when(config.message(any(), anyString())).thenAnswer(i -> i.getArgument(1));
        CommandSender admin = mock(CommandSender.class);
        when(admin.hasPermission(anyString())).thenReturn(true);
        Runnable reload = mock(Runnable.class);
        var command = new InsomniaCommand(config, mock(FatigueService.class), null, null, null, null, reload, null);
        for (String sub : List.of("get", "setfatigue", "setdebt", "sleep", "effect", "spawnmob", "overview"))
            command.execute(admin, "insomnia", "admin", sub, "Alex", "10");
        verify(admin, times(7)).sendMessage("feature_off");
        command.execute(admin, "insomnia", "admin", "reload");
        verify(reload).run();
        verify(admin).sendMessage("reloaded");
    }

    @Test void givenFeaturesSwitched_whenTabCompleting_thenTheSameTreeFollowsTheSwitchAtOnce() {
        InsomniaConfig config = SwitchDefaults.on(mock(InsomniaConfig.class));
        when(config.menuEnabled()).thenReturn(false);
        when(config.adminToolsEnabled()).thenReturn(false);
        when(config.dreamEnabled()).thenReturn(true);
        when(config.dreamQuestsEnabled()).thenReturn(false);
        when(config.dreamCompanionsEnabled()).thenReturn(true);
        when(config.dreamWeeklyEnabled()).thenReturn(true);
        var specs = InsomniaCommands.specs(null, null, config);
        var insomnia = specs.get(0);
        var admin = insomnia.children().get("admin");
        var dream = specs.get(1);
        for (String sub : List.of("menu", "status", "history", "triggers")) assertFalse(insomnia.children().get(sub).visibleTo(null), sub);
        for (String sub : List.of("get", "setfatigue", "sleep", "overview")) assertFalse(admin.children().get(sub).visibleTo(null), sub);
        assertTrue(admin.children().get("reload").visibleTo(null));
        assertFalse(dream.children().get("quest").visibleTo(null));
        assertTrue(dream.children().get("weekly").visibleTo(null));
        assertTrue(dream.visibleTo(null));
        // the same built trees, switches flipped
        when(config.menuEnabled()).thenReturn(true);
        when(config.adminToolsEnabled()).thenReturn(true);
        when(config.dreamQuestsEnabled()).thenReturn(true);
        when(config.dreamEnabled()).thenReturn(false);
        for (String sub : List.of("menu", "status", "history", "triggers")) assertTrue(insomnia.children().get(sub).visibleTo(null), sub);
        for (String sub : List.of("get", "setfatigue", "sleep", "overview")) assertTrue(admin.children().get(sub).visibleTo(null), sub);
        assertTrue(dream.children().get("quest").visibleTo(null));
        for (int i = 1; i <= 3; i++) assertFalse(specs.get(i).visibleTo(null), specs.get(i).name() + " root follows dream.enabled");
        for (var spec : specs) assertTrue(spec.children().values().stream().noneMatch(c -> c.hidden()), "never hidden for good");
        for (var spec : InsomniaCommands.specs(null, null))
            assertTrue(spec.children().values().stream().allMatch(c -> c.visibleTo(null)), "without config everything is listed");
    }

    @Test void givenReload_whenASwitchChanged_thenCommandListsAreRefreshedAndNothingIsRegisteredAgain() throws Exception {
        VystormInsomniaPlugin plugin = mock(VystormInsomniaPlugin.class, CALLS_REAL_METHODS);
        InsomniaConfig config = SwitchDefaults.on(mock(InsomniaConfig.class));
        var api = mock(Vystorm.vystorm_core.API.class);
        var commands = mock(Vystorm.vystorm_core._uiMethods.commands.CommandInterface.class);
        when(api.command()).thenReturn(commands);
        for (var field : List.of("config", "api", "core", "stimulants", "mobs")) {
            var f = VystormInsomniaPlugin.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(plugin, switch (field) {
                case "config" -> config;
                case "api" -> api;
                case "core" -> mock(at.vystorm.insomnia.core.CoreBridge.class);
                case "stimulants" -> mock(StimulantService.class);
                default -> mock(InsomniaMobService.class);
            });
        }
        plugin.reloadRuntimeConfig();
        verify(commands, never()).refresh();
        doAnswer(invocation -> { when(config.menuEnabled()).thenReturn(false); return null; }).when(config).reload();
        plugin.reloadRuntimeConfig();
        verify(commands, times(1)).refresh();
        verify(commands, never()).register(any(), any(), any());
    }

    @Test void givenClientHudOff_whenTicked_thenNoBarIsSentAndNoToast() throws IOException {
        ClientUiInterface clientUi = mock(ClientUiInterface.class);
        ClientUi ui = mock(ClientUi.class);
        when(clientUi.of(any())).thenReturn(ui);
        FatigueService fatigue = mock(FatigueService.class);
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(fatigue.state(id)).thenReturn(Optional.of(new PlayerFatigueState(id, 1000)));
        when(ui.available(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);
        when(ui.visible(any())).thenReturn(true);
        when(player.locale()).thenReturn(java.util.Locale.US);
        String en;
        try (InputStream in = resource("lang/en.yml")) { en = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        Messages texts = new Messages(Vystorm.vystorm_core.lang.Lang.create("VystormInsomnia", java.util.Map.of("en", en)));
        FatigueHud hud = new FatigueHud(mock(org.bukkit.plugin.Plugin.class), clientUi, texts, fatigue);
        boolean[] on = {false};
        hud.enabledWhen(() -> on[0]);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            hud.tick();
            verify(ui, never()).put(any(), any(Vystorm.vystorm_core.web.ClientElement.Element.class));
            assertFalse(hud.stageChanged(player, at.vystorm.insomnia.api.InsomniaStage.TIRED, "stage_tired"));
            on[0] = true;
            hud.tick();
            verify(ui, atLeastOnce()).put(eq(player), any(Vystorm.vystorm_core.web.ClientElement.Element.class));
        }
        verify(ui, atLeastOnce()).available(player, ClientServices.CAP_HUD);
    }

    @Test void givenWebPageAndTutorial_whenRead_thenStartupSwitches() throws IOException {
        InsomniaConfig off = with("web_page.enabled", false, "tutorial.enabled", false);
        assertFalse(off.webPageEnabled());
        assertFalse(off.tutorialEnabled());
        assertTrue(InsomniaConfigEditor.RESTART.containsAll(List.of("web_page", "tutorial", "integrations")));
    }

    private static <T> T eq(T value) { return org.mockito.ArgumentMatchers.eq(value); }
}
