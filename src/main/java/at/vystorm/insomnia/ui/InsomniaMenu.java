package at.vystorm.insomnia.ui;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.compat.dialog.DialogButton;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core._uiMethods.menu.MenuCategory;
import Vystorm.vystorm_core._uiMethods.settings.ConfigSettings;
import at.vystorm.insomnia.api.InsomniaStage;
import at.vystorm.insomnia.model.PlayerFatigueState;
import at.vystorm.insomnia.service.FatigueService;
import at.vystorm.insomnia.service.RestedBonusService;
import at.vystorm.insomnia.service.SleepService;
import Vystorm.vystorm_core.compat.dialog.DialogBody;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Native "Müdigkeit" status dialog and the admin settings section. */
public final class InsomniaMenu {
    public static final String ADMIN = "insomnia.admin";

    private final Plugin plugin;
    private final API api;
    private final FatigueService fatigue;
    private final SleepService sleep;
    private final RestedBonusService rested;
    private final Runnable reload;
    private final Messages texts;
    private FatiguePanel panel;
    private InsomniaWeb web;
    private FatigueOverview overview;

    public InsomniaMenu(Plugin plugin, API api, Messages texts, FatigueService fatigue, SleepService sleep,
                        RestedBonusService rested, Runnable reload) {
        this.texts = Objects.requireNonNull(texts, "texts");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.api = Objects.requireNonNull(api, "api");
        this.fatigue = Objects.requireNonNull(fatigue, "fatigue");
        this.sleep = Objects.requireNonNull(sleep, "sleep");
        this.rested = Objects.requireNonNull(rested, "rested");
        this.reload = Objects.requireNonNull(reload, "reload");
    }

    public static String settingsKey(Plugin plugin) {
        return plugin.getName().toLowerCase(Locale.ROOT) + ":server";
    }

    /**
     * Registers the server settings and the {@code /vmenu} entry "Müdigkeit". With the client mod's panels
     * ({@code panel} non-null) the entry is the native {@link FatiguePanel} with this dialog as fallback; otherwise
     * the dialog itself.
     */
    public void register(FatiguePanel panel, InsomniaWeb web) {
        this.panel = panel;
        this.web = web;
        if (panel != null) panel.register(this::open, web != null);
        else api.gui().registerMenu(plugin, FatiguePanel.NAME, texts.ref("menu.title"), Material.RED_BED,
                p -> enabled.getAsBoolean() && p.hasPermission("insomnia.use"), this::open, MenuCategory.CHARACTER.group(),
                texts.ref("menu.description"));
        api.settings().register(plugin, settings(plugin, texts::ref).section("server", texts.ref("settings.title"),
                texts.ref("settings.description"), ADMIN, this::applySettings));
    }

    /** The admin overview behind the "All players" button (shown to admins only). */
    public void overview(FatigueOverview overview) { this.overview = overview; }

    /** Opens the status: native panel for players with the mod (it falls back to {@link #open}), else the dialog. */
    public void show(Player player) {
        if (!enabled.getAsBoolean()) { player.sendMessage(texts.chat(player, "feature_off")); return; }
        if (panel != null) panel.open(player); else open(player);
    }

    private java.util.function.BooleanSupplier enabled = () -> true;

    /** {@code menu.enabled} (0.31.0): while it is false the menu entry is hidden and the menu does not open. */
    public void enabledWhen(java.util.function.BooleanSupplier enabled) { this.enabled = Objects.requireNonNull(enabled, "enabled"); }

    /** Settings are already written to config.yml; an invalid combination keeps the previous values active. */
    private void applySettings(Player admin) {
        try {
            reload.run();
        } catch (IllegalArgumentException invalid) {
            admin.sendMessage(texts.chat(admin, "config_invalid", "errors", invalid.getMessage()));
        }
    }

    /**
     * Editable config keys; slider bounds keep the validated ranges of InsomniaConfig. Labels and descriptions are
     * {@code Lang.ref}s ({@code setting.<path>.label|desc}) so Core shows them in each admin's language.
     */
    public static ConfigSettings settings(Plugin plugin, java.util.function.Function<String, String> ref) {
        // never saved over a config.yml with a YAML error (Core's ConfigSettings.of(plugin) would write getConfig() over it)
        return ConfigSettings.of(plugin::getConfig, () -> at.vystorm.insomnia.config.YamlCheck.saveConfig(plugin))
                .toggle("fatigue.enabled", ref.apply("setting.fatigue.enabled.label"), ref.apply("setting.fatigue.enabled.desc"))
                .decimal("fatigue.base_per_minute", ref.apply("setting.fatigue.base_per_minute.label"), ref.apply("setting.fatigue.base_per_minute.desc"), 0.0D, 1.0D, 0.005D)
                .decimal("fatigue.debt_per_minute", ref.apply("setting.fatigue.debt_per_minute.label"), ref.apply("setting.fatigue.debt_per_minute.desc"), 0.0D, 1.0D, 0.0025D)
                .decimal("sleep.fatigue_recovery_per_second", ref.apply("setting.sleep.fatigue_recovery_per_second.label"), ref.apply("setting.sleep.fatigue_recovery_per_second.desc"), 0.0D, 5.0D, 0.1D)
                .decimal("sleep.debt_recovery_per_second", ref.apply("setting.sleep.debt_recovery_per_second.label"), ref.apply("setting.sleep.debt_recovery_per_second.desc"), 0.0D, 5.0D, 0.1D)
                .toggle("sleep.cancel_vanilla_night_skip", ref.apply("setting.sleep.cancel_vanilla_night_skip.label"), ref.apply("setting.sleep.cancel_vanilla_night_skip.desc"))
                .toggle("sleep.dialog.enabled", ref.apply("setting.sleep.dialog.enabled.label"), ref.apply("setting.sleep.dialog.enabled.desc"))
                .toggle("sleep.acceleration.enabled", ref.apply("setting.sleep.acceleration.enabled.label"), ref.apply("setting.sleep.acceleration.enabled.desc"))
                .decimal("sleep.acceleration.min_speed", ref.apply("setting.sleep.acceleration.min_speed.label"), ref.apply("setting.sleep.acceleration.min_speed.desc"), 1.0D, 100.0D, 0.5D)
                .decimal("sleep.acceleration.max_speed", ref.apply("setting.sleep.acceleration.max_speed.label"), ref.apply("setting.sleep.acceleration.max_speed.desc"), 1.0D, 100.0D, 0.5D)
                .toggle("stimulants.enabled", ref.apply("setting.stimulants.enabled.label"), ref.apply("setting.stimulants.enabled.desc"))
                .toggle("hallucinations.enabled", ref.apply("setting.hallucinations.enabled.label"), ref.apply("setting.hallucinations.enabled.desc"))
                .decimal("hallucinations.chance_exhausted", ref.apply("setting.hallucinations.chance_exhausted.label"), ref.apply("setting.hallucinations.chance_exhausted.desc"), 0.0D, 1.0D, 0.01D)
                .decimal("hallucinations.chance_overmuedet", ref.apply("setting.hallucinations.chance_overmuedet.label"), ref.apply("setting.hallucinations.chance_overmuedet.desc"), 0.0D, 1.0D, 0.01D)
                .decimal("hallucinations.chance_sleep_deprivation", ref.apply("setting.hallucinations.chance_sleep_deprivation.label"), ref.apply("setting.hallucinations.chance_sleep_deprivation.desc"), 0.0D, 1.0D, 0.01D)
                .toggle("microsleep.enabled", ref.apply("setting.microsleep.enabled.label"), ref.apply("setting.microsleep.enabled.desc"))
                .decimal("microsleep.min_fatigue", ref.apply("setting.microsleep.min_fatigue.label"), ref.apply("setting.microsleep.min_fatigue.desc"), 0.0D, 100.0D, 1.0D)
                .decimal("microsleep.chance_per_check", ref.apply("setting.microsleep.chance_per_check.label"), ref.apply("setting.microsleep.chance_per_check.desc"), 0.0D, 1.0D, 0.01D)
                .integer("microsleep.cooldown_seconds", ref.apply("setting.microsleep.cooldown_seconds.label"), ref.apply("setting.microsleep.cooldown_seconds.desc"), 10, 1800, 10)
                .toggle("insomnia_mobs.enabled", ref.apply("setting.insomnia_mobs.enabled.label"), ref.apply("setting.insomnia_mobs.enabled.desc"))
                .decimal("insomnia_mobs.min_fatigue", ref.apply("setting.insomnia_mobs.min_fatigue.label"), ref.apply("setting.insomnia_mobs.min_fatigue.desc"), 0.0D, 100.0D, 1.0D)
                .decimal("insomnia_mobs.base_spawn_chance", ref.apply("setting.insomnia_mobs.base_spawn_chance.label"), ref.apply("setting.insomnia_mobs.base_spawn_chance.desc"), 0.0D, 1.0D, 0.01D)
                .toggle("rested.enabled", ref.apply("setting.rested.enabled.label"), ref.apply("setting.rested.enabled.desc"))
                .integer("rested.duration_minutes", ref.apply("setting.rested.duration_minutes.label"), ref.apply("setting.rested.duration_minutes.desc"), 5, 240, 5)
                .decimal("rested.xp_multiplier", ref.apply("setting.rested.xp_multiplier.label"), ref.apply("setting.rested.xp_multiplier.desc"), 1.0D, 2.0D, 0.01D)
                .toggle("rest.campfire.enabled", ref.apply("setting.rest.campfire.enabled.label"), ref.apply("setting.rest.campfire.enabled.desc"))
                .decimal("rest.campfire.fatigue_per_second", ref.apply("setting.rest.campfire.fatigue_per_second.label"), ref.apply("setting.rest.campfire.fatigue_per_second.desc"), 0.0D, 2.0D, 0.05D)
                .decimal("rest.campfire.min_fatigue", ref.apply("setting.rest.campfire.min_fatigue.label"), ref.apply("setting.rest.campfire.min_fatigue.desc"), 0.0D, 100.0D, 1.0D)
                .toggle("player_menu.enabled", ref.apply("setting.player_menu.enabled.label"), ref.apply("setting.player_menu.enabled.desc"))
                .toggle("player_menu.show_stage_to_players", ref.apply("setting.player_menu.show_stage_to_players.label"), ref.apply("setting.player_menu.show_stage_to_players.desc"))
                .toggle("integrations.cityclaim", ref.apply("setting.integrations.cityclaim.label"), ref.apply("setting.integrations.cityclaim.desc"))
                .toggle("integrations.bloodmoon", ref.apply("setting.integrations.bloodmoon.label"), ref.apply("setting.integrations.bloodmoon.desc"));
    }

    public void open(Player player) {
        if (!player.hasPermission("insomnia.use")) {
            player.sendMessage(texts.chat(player, "no_permission"));
            return;
        }
        if (!fatigue.ready(player.getUniqueId())) {
            player.sendMessage(texts.chat(player, "data_loading"));
            return;
        }
        PlayerFatigueState state = fatigue.require(player.getUniqueId());
        long now = System.currentTimeMillis();
        List<Component> lines = new ArrayList<>();
        InsomniaStage stage = state.insomniaStage();
        lines.add(texts.component(player, "dialog.state", "stage",
                Component.text(texts.stageName(player, stage), stageColor(stage))));
        lines.add(texts.component(player, "dialog.fatigue", "bar", bar(state.fatigue()), "value", fmt(state.fatigue())));
        lines.add(texts.component(player, "dialog.debt", "bar", bar(state.sleepDebt()), "value", fmt(state.sleepDebt())));
        lines.add(texts.component(player, "dialog.rested", "rested",
                texts.restedLeft(player, rested.remainingMinutes(player.getUniqueId(), now))));
        if (state.stimulantUntilEpochMillis() > now) {
            lines.add(texts.component(player, "dialog.stimulant", "minutes",
                    Math.max(1L, (state.stimulantUntilEpochMillis() - now + 59_999L) / 60_000L)));
        }
        if (sleep.isSleeping(player.getUniqueId())) lines.add(texts.component(player, "dialog.sleeping"));
        lines.add(Component.text(texts.hint(player, stage), NamedTextColor.GRAY));

        List<DialogButton> buttons = new ArrayList<>();
        buttons.add(new DialogButton(texts.component(player, "dialog.refresh"), (p, r) -> open(p)));
        var coreLang = api.lang().core();
        if (player.hasPermission(ADMIN)) buttons.add(new DialogButton(coreLang.translatable("menu.settings-button"), (p, r) -> {
            if (!api.settings().openSection(p, settingsKey(plugin))) coreLang.send(p, "common.settings-unavailable");
        }));
        if (overview != null && player.hasPermission(ADMIN))
            buttons.add(new DialogButton(texts.component(player, "overview.button"), (p, r) -> overview.open(p)));
        if (web != null && web.available()) buttons.add(new DialogButton(texts.component(player, "dialog.web"), (p, r) -> web.open(p, null)));
        buttons.add(new DialogButton(Component.text("← ").append(coreLang.translatable(MenuCategory.CHARACTER.langKey() + ".title")),
                (p, r) -> api.gui().openDirectory(p, 0, MenuCategory.CHARACTER.group())));
        buttons.add(new DialogButton(coreLang.translatable("menu.home"), (p, r) -> api.gui().openDirectory(p, 0)));
        api.dialogs().open(plugin, player, new DialogRequest(texts.component(player, "dialog.title"),
                lines.stream().map(line -> (DialogBody) DialogBody.plainMessage(line, 300)).toList(), List.of(), buttons,
                p -> p.isOnline() && p.hasPermission("insomnia.use"), () -> {}, texts.text(player, "dialog.close"), 2));
    }

    private static NamedTextColor stageColor(InsomniaStage stage) {
        return switch (stage) {
            case RESTED -> NamedTextColor.GREEN;
            case TIRED -> NamedTextColor.YELLOW;
            case EXHAUSTED -> NamedTextColor.GOLD;
            case OVERMUEDET -> NamedTextColor.RED;
            case SLEEP_DEPRIVATION -> NamedTextColor.DARK_RED;
        };
    }

    /** Ten-segment bar for a 0-100 value. */
    static String bar(double value) {
        int filled = (int) Math.round(Math.max(0.0D, Math.min(100.0D, value)) / 10.0D);
        return "█".repeat(filled) + "░".repeat(10 - filled);
    }

    private static String fmt(double value) { return String.format(Locale.GERMAN, "%.1f", value); }
}
