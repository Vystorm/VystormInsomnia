package at.vystorm.insomnia.command;

import at.vystorm.insomnia.api.InsomniaStage;
import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.model.PlayerFatigueState;
import at.vystorm.insomnia.service.FatigueService;
import at.vystorm.insomnia.service.HallucinationService;
import at.vystorm.insomnia.service.InsomniaMobService;
import at.vystorm.insomnia.service.RestedBonusService;
import at.vystorm.insomnia.service.SleepService;
import at.vystorm.insomnia.ui.InsomniaMenu;
import at.vystorm.insomnia.ui.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class InsomniaCommand implements CommandExecutor, TabCompleter {
    private final InsomniaConfig config;
    private final FatigueService fatigue;
    private final SleepService sleep;
    private final HallucinationService hallucinations;
    private final InsomniaMobService mobs;
    private final RestedBonusService rested;
    private final Runnable reload;
    private final InsomniaMenu menu;
    private at.vystorm.insomnia.ui.FatigueOverview overview;
    private at.vystorm.insomnia.service.CampfireRestService campfire;

    public InsomniaCommand(InsomniaConfig config, FatigueService fatigue, SleepService sleep,
                           HallucinationService hallucinations, InsomniaMobService mobs,
                           RestedBonusService rested, Runnable reload, InsomniaMenu menu) {
        this.config = config;
        this.fatigue = fatigue;
        this.sleep = sleep;
        this.hallucinations = hallucinations;
        this.mobs = mobs;
        this.rested = rested;
        this.reload = reload;
        this.menu = menu;
    }

    /** The one message every switched-off feature answers with (0.31.0). */
    public static final String OFF = "feature_off";
    public static final String USE = "insomnia.use";
    public static final String ADMIN = "insomnia.admin";
    /** First-level subcommands for players with {@link #USE}; "admin" needs {@link #ADMIN}. */
    public static final List<String> PLAYER_SUBCOMMANDS = List.of("menu", "status", "history", "triggers");
    public static final List<String> ADMIN_SUBCOMMANDS = List.of("get", "setfatigue", "setdebt", "sleep", "effect", "spawnmob", "reload", "overview");

    /** Admin overview for {@code /insomnia admin overview}; without it the subcommand shows the admin usage. */
    public void overview(at.vystorm.insomnia.ui.FatigueOverview overview) { this.overview = overview; }

    /** Campfire rest, for the "resting" line in the status. */
    public void campfire(at.vystorm.insomnia.service.CampfireRestService campfire) { this.campfire = campfire; }

    /**
     * Runs {@code /insomnia} with English subcommand ids (Core's command tree translates localized names first).
     * {@code false}: no such command form, show the usage.
     */
    public boolean execute(CommandSender sender, String label, String... args) {
        return onCommand(sender, null, label, args);
    }

    /** Hallucination effect ids for completion of {@code admin effect}. */
    List<String> effectNames() { return hallucinations == null ? List.of() : List.copyOf(hallucinations.effects()); }

    /** Mob tier ids for completion of {@code admin spawnmob}. */
    List<String> mobTiers() { return mobs == null ? List.of() : mobs.tierIds().stream().map(String::valueOf).toList(); }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        boolean adminCommand = args.length > 0 && args[0].equalsIgnoreCase("admin");
        if (!adminCommand && !sender.hasPermission(USE)) {
            sender.sendMessage(config.message(sender, "no_permission"));
            return true;
        }
        if (!adminCommand && !config.menuEnabled()) {
            sender.sendMessage(config.message(sender, OFF));
            return true;
        }
        if (menu != null && sender instanceof Player self && (args.length == 0 || args[0].equalsIgnoreCase("menu"))) {
            menu.show(self);
            return true;
        }
        if (args.length >= 1 && args.length <= 2 && (args[0].equalsIgnoreCase("history") || args[0].equalsIgnoreCase("triggers"))) {
            if (!(sender instanceof Player player)) { sender.sendMessage(config.message(sender, "player_only")); return true; }
            if (!fatigue.ready(player.getUniqueId())) { sender.sendMessage(config.message(sender, "data_loading")); return true; }
            boolean triggers = args[0].equalsIgnoreCase("triggers");
            var entries = fatigue.recentHistory(player.getUniqueId()).reversed().stream()
                    .filter(entry -> !triggers || entry.marker().startsWith("hallucination:")).toList();
            int pages = Math.max(1, (entries.size() + 7) / 8);
            Integer page = args.length == 2 ? integer(args, 1) : 1;
            if (page == null || page < 1 || page > pages) {
                sender.sendMessage(text(sender, "history.invalid_page", "pages", pages)); return true;
            }
            sender.sendMessage(text(sender, triggers ? "history.triggers_title" : "history.title"));
            sender.sendMessage(text(sender, "history.page", "page", page, "pages", pages));
            int shown = 0;
            for (var entry : entries.stream().skip((long) (page - 1) * 8).limit(8).toList()) {
                String marker = entry.marker();
                String detail = marker.equals("sample") ? "history.sample" : marker.equals("sleep_started") ? "history.sleep_started" :
                        marker.equals("sleep_completed") ? "history.sleep_completed" : marker.equals("rest_started") ? "history.rest_started" : marker.startsWith("stimulant:") ? "history.stimulant" :
                        marker.startsWith("hallucination:") ? "history.hallucination" : "history.sample";
                String event;
                if (marker.startsWith("hallucination:")) {
                    String[] parts = marker.split(":");
                    String stage = parts.length > 2 ? parts[2] : "unknown";
                    try { stage = stageName(sender, at.vystorm.insomnia.api.InsomniaStage.valueOf(stage.toUpperCase(Locale.ROOT))); }
                    catch (IllegalArgumentException ignored) { }
                    String triggerFatigue = parts.length > 3 ? parts[3].replace("fatigue=", "") : "?";
                    String triggerDebt = parts.length > 4 ? parts[4].replace("debt=", "") : "?";
                    event = text(sender, "history.hallucination", "effect", parts.length > 1 ? parts[1] : "?",
                            "stage", stage, "fatigue", triggerFatigue, "debt", triggerDebt);
                } else {
                    String suffix = marker.startsWith("stimulant:") ? marker.substring("stimulant:".length()) : "";
                    event = text(sender, detail, "detail", suffix);
                }
                sender.sendMessage(text(sender, "history.entry", "at", java.time.Instant.ofEpochMilli(entry.atMillis()).toString(),
                        "fatigue", fmt(entry.fatigue()), "debt", fmt(entry.debt()), "event", event));
                if (++shown >= 8) break;
            }
            if (shown == 0) sender.sendMessage(text(sender, triggers ? "history.no_triggers" : "history.empty"));
            sender.sendMessage(text(sender, "history.memory_only"));
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(config.message(sender, "player_only"));
                return true;
            }
            status(sender, player);
            return true;
        }

        if (!args[0].equalsIgnoreCase("admin")) return false;
        if (!sender.hasPermission("insomnia.admin")) {
            sender.sendMessage(config.message(sender, "no_permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(config.message(sender, "admin_usage"));
            return true;
        }

        String sub = args[1].toLowerCase(Locale.ROOT);
        if (!ADMIN_SUBCOMMANDS.contains(sub)) {
            sender.sendMessage(config.message(sender, "admin_usage"));
            return true;
        }
        // admin_tools.enabled false (0.31.0): every admin tool is off; reload stays, it applies the switches.
        if (!sub.equals("reload") && !config.adminToolsEnabled()) {
            sender.sendMessage(config.message(sender, OFF));
            return true;
        }
        if (sub.equals("overview")) {
            if (overview != null) overview.open(sender);
            else sender.sendMessage(config.message(sender, "admin_usage"));
            return true;
        }
        if (sub.equals("reload")) {
            try {
                reload.run();
                sender.sendMessage(config.message(sender, "reloaded"));
            } catch (IllegalArgumentException invalid) {
                sender.sendMessage(config.message(sender, "config_invalid", "errors", invalid.getMessage()));
            }
            return true;
        }
        if (args.length < 3 || (args.length < 4 && needsValue(sub))) {
            sender.sendMessage(adminUsage(sub));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            sender.sendMessage(config.message(sender, "player_not_found"));
            return true;
        }
        if (!fatigue.ready(target.getUniqueId())) {
            sender.sendMessage(config.message(sender, "data_loading"));
            return true;
        }
        fatigue.require(target.getUniqueId());

        switch (sub) {
            case "get" -> status(sender, target);
            case "setfatigue" -> {
                Double value = number(args, 3, fatigue.fatigue(target.getUniqueId()));
                if (value == null) sender.sendMessage(config.message(sender, "invalid_number"));
                else {
                    fatigue.setFatigue(target.getUniqueId(), rawForShown(fatigue.require(target.getUniqueId()), value));
                    sender.sendMessage(text(sender, "admin.set_fatigue", "player", target.getName(), "value", fmt(fatigue.fatigue(target.getUniqueId()))));
                }
            }
            case "setdebt" -> {
                Double value = number(args, 3, fatigue.sleepDebt(target.getUniqueId()));
                if (value == null) sender.sendMessage(config.message(sender, "invalid_number"));
                else {
                    fatigue.setDebt(target.getUniqueId(), value);
                    sender.sendMessage(text(sender, "admin.set_debt", "player", target.getName(), "value", fmt(fatigue.sleepDebt(target.getUniqueId()))));
                }
            }
            case "sleep" -> {
                sleep.forceRest(target, System.currentTimeMillis());
                sender.sendMessage(text(sender, "admin.rested", "player", target.getName()));
            }
            case "effect" -> {
                if (args.length < 4 || !hallucinations.force(target, args[3])) sender.sendMessage(text(sender, "admin.effect_unknown", "effects", hallucinations.effects()));
                else sender.sendMessage(text(sender, "admin.effect_done"));
            }
            case "spawnmob" -> {
                Integer tier = integer(args, 3);
                if (tier == null) sender.sendMessage(config.message(sender, "invalid_number"));
                else sender.sendMessage(text(sender, mobs.spawn(target, tier, System.currentTimeMillis()) ? "admin.mob_spawned" : "admin.mob_refused"));
            }
            default -> sender.sendMessage(config.message(sender, "admin_usage"));
        }
        return true;
    }

    private void status(CommandSender sender, Player target) {
        if (!fatigue.ready(target.getUniqueId())) {
            sender.sendMessage(config.message(sender, "data_loading"));
            return;
        }
        PlayerFatigueState state = fatigue.require(target.getUniqueId());
        String message = config.message(sender, "status",
                "fatigue", fmt(state.fatigue()),
                "debt", fmt(state.sleepDebt()),
                "stage", stageName(sender, state.insomniaStage()),
                "rested", restedLeft(sender, target));
        sender.sendMessage((sender.equals(target) ? "" : target.getName() + ": ") + message);
        var stimulant = at.vystorm.insomnia.service.StimulantForecast.of(state, System.currentTimeMillis());
        if (stimulant != null) sender.sendMessage(text(sender, "status_stimulant",
                "minutes", stimulant.seconds() / 60, "seconds", stimulant.seconds() % 60,
                "uses", stimulant.uses(), "fatigue", fmt(stimulant.fatigueAfterCrash())));
        long grace = at.vystorm.insomnia.service.MicrosleepService.recoveryRemaining(target, System.currentTimeMillis());
        if (grace > 0) sender.sendMessage(config.message(sender, "microsleep_grace", "seconds", (grace + 999) / 1000));
        if (target.isOnline() && sleep != null) {
            org.bukkit.Location bed = nearbyBed(target);
            if (bed != null && config.cozyEnabled()) sender.sendMessage(sleep.cozyLine(sender, "status_cozy", sleep.comfortAt(bed)));
        }
        if (target.isOnline()) {
            String reason = fatigue.pausedReason(target);
            if (campfire != null && campfire.resting(target.getUniqueId())) sender.sendMessage(text(sender, "rest.status"));
            if ("local_afk".equals(reason)) sender.sendMessage(text(sender, "fatigue.paused_local_afk", "seconds", config.localAfkThresholdSeconds()));
            else if (reason != null) sender.sendMessage(text(sender, "fatigue.paused_" + reason));
            else if (config.fatigueEnabled() && !target.isSleeping()) nextStage(sender, state.fatigue());
        }
    }

    /** "Next stage X in about 1 h 20 min of normal play", estimated from the base rate without activity bonuses. */
    private void nextStage(CommandSender sender, double fatigueValue) {
        List<Double> thresholds = config.stageThresholds();
        long minutes = minutesToNextStage(fatigueValue, thresholds, config.baseFatiguePerMinute());
        if (minutes < 0) return;
        InsomniaStage next = InsomniaStage.values()[InsomniaStage.fromFatigue(fatigueValue, thresholds).ordinal() + 1];
        sender.sendMessage(minutes < 60
                ? text(sender, "status_next.minutes", "stage", stageName(sender, next), "minutes", Math.max(1, minutes))
                : text(sender, "status_next.hours", "stage", stageName(sender, next), "hours", minutes / 60, "minutes", minutes % 60));
    }

    /**
     * Whole minutes (rounded up) until fatigue reaches the next stage threshold at {@code perMinute}, or -1 when
     * the player is already in the last stage or fatigue does not grow.
     */
    static long minutesToNextStage(double fatigue, List<Double> thresholds, double perMinute) {
        if (!(perMinute > 0) || thresholds == null) return -1;
        for (double threshold : thresholds) {
            if (fatigue < threshold) return (long) Math.ceil((threshold - fatigue) / perMinute);
        }
        return -1;
    }

    /** The player's own position when sleeping or standing within two blocks of a bed, else null. */
    static org.bukkit.Location nearbyBed(Player player) {
        if (player.isSleeping()) return player.getLocation();
        org.bukkit.Location at = player.getLocation();
        for (int dx = -2; dx <= 2; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -2; dz <= 2; dz++) {
            var candidate = at.clone().add(dx, dy, dz);
            if (candidate.getWorld() == null || !candidate.getWorld().isChunkLoaded(candidate.getBlockX() >> 4, candidate.getBlockZ() >> 4)) continue;
            if (org.bukkit.Tag.BEDS.isTagged(candidate.getBlock().getType())) return at;
        }
        return null;
    }

    private String text(CommandSender viewer, String key, Object... placeholders) {
        Messages texts = config.messages();
        return texts == null ? key : texts.text(viewer, key, placeholders);
    }

    private String stageName(CommandSender viewer, at.vystorm.insomnia.api.InsomniaStage stage) {
        Messages texts = config.messages();
        return texts == null ? stage.name() : texts.stageName(viewer, stage);
    }

    private String restedLeft(CommandSender viewer, Player target) {
        long minutes = rested.remainingMinutes(target.getUniqueId(), System.currentTimeMillis());
        Messages texts = config.messages();
        return texts == null ? Long.toString(minutes) : texts.restedLeft(viewer, minutes);
    }

    private static boolean needsValue(String sub) {
        // "effect" without a name keeps listing the available effects.
        return sub.equals("setfatigue") || sub.equals("setdebt") || sub.equals("spawnmob");
    }

    /** Usage line for one admin subcommand, shown when its player or value argument is missing. */
    static String adminUsage(String sub) {
        return switch (sub) {
            case "setfatigue", "setdebt" -> "/insomnia admin " + sub + " <player> <0-100|+n|-n>";
            case "effect" -> "/insomnia admin effect <player> <effect>";
            case "spawnmob" -> "/insomnia admin spawnmob <player> <tier>";
            case "reload" -> "/insomnia admin reload";
            case "overview" -> "/insomnia admin overview";
            default -> "/insomnia admin " + sub + " <player>";
        };
    }

    /**
     * Parses a 0-100 admin value. A leading {@code +} or {@code -} changes {@code current} by that amount instead
     * of setting it. The result is clamped to 0-100; NaN and infinities are rejected so they never reach player data.
     */
    static Double number(String[] args, int index, double current) {
        if (args.length <= index) return null;
        String raw = args[index].trim();
        boolean relative = raw.startsWith("+") || raw.startsWith("-");
        try {
            double value = Double.parseDouble(raw);
            if (!Double.isFinite(value)) return null;
            if (relative) value += current;
            return Math.max(0.0, Math.min(100.0, value));
        } catch (NumberFormatException ignored) { return null; }
    }

    /**
     * Raw fatigue to store so the shown (effective) value becomes {@code shown}. The shown value is raw minus an active
     * stimulant suppression, so storing {@code shown} as raw would lower it by the suppression a second time.
     */
    static double rawForShown(PlayerFatigueState state, double shown) {
        return state.rawFatigue() + shown - state.fatigue();
    }

    private static Integer integer(String[] args, int index) {
        if (args.length <= index) return null;
        try { return Integer.parseInt(args[index]); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static String fmt(double value) { return String.format(Locale.ROOT, "%.1f", value); }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        List<String> values = new ArrayList<>();
        boolean admin = sender.hasPermission(ADMIN);
        if (args.length == 0) return values;
        if (args.length == 1) {
            if (sender.hasPermission(USE)) values.addAll(PLAYER_SUBCOMMANDS);
            if (admin) values.add("admin");
        } else if (!args[0].equalsIgnoreCase("admin") || !admin) {
            return List.of();
        } else if (args.length == 2) values.addAll(ADMIN_SUBCOMMANDS);
        else if (args.length == 3 && !args[1].equalsIgnoreCase("reload") && !args[1].equalsIgnoreCase("overview")) {
            for (Player player : Bukkit.getOnlinePlayers()) values.add(player.getName());
        } else if (args.length == 4 && args[1].equalsIgnoreCase("effect")) values.addAll(hallucinations.effects());
        else if (args.length == 4 && args[1].equalsIgnoreCase("spawnmob")) values.addAll(mobs.tierIds().stream().map(String::valueOf).toList());
        else if (args.length == 4 && (args[1].equalsIgnoreCase("setfatigue") || args[1].equalsIgnoreCase("setdebt")))
            values.addAll(List.of("0", "25", "50", "75", "100", "+10", "-10"));
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
