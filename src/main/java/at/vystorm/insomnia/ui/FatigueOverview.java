package at.vystorm.insomnia.ui;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.compat.dialog.DialogButton;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import at.vystorm.insomnia.api.InsomniaStage;
import at.vystorm.insomnia.model.PlayerFatigueState;
import at.vystorm.insomnia.service.CampfireRestService;
import at.vystorm.insomnia.service.FatigueService;
import at.vystorm.insomnia.service.RestedBonusService;
import at.vystorm.insomnia.service.SleepService;
import Vystorm.vystorm_core.compat.dialog.DialogBody;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Admin sleep overview ({@code /insomnia admin overview}, button in the fatigue dialog, Core player menu): every
 * online player sorted by fatigue, and per player a dialog with the values and quick actions (fully rest, fatigue
 * -20/+20, clear sleep debt). Replaces typing {@code /insomnia admin setfatigue <player> ...}. Server thread.
 */
public final class FatigueOverview {
    public static final String ADMIN = "insomnia.admin";
    /** Most players listed as buttons; the rest is summed up in one line. */
    static final int MAX_ROWS = 40;
    /** Fatigue change of the -/+ buttons. */
    static final double STEP = 20.0;

    /** One online player in the overview. */
    public record Row(UUID id, String name, InsomniaStage stage, double fatigue, double debt, boolean sleeping, boolean resting) {}

    private final Plugin plugin;
    private final API api;
    private final Messages texts;
    private final FatigueService fatigue;
    private final SleepService sleep;
    private final RestedBonusService rested;
    private final CampfireRestService campfire;
    private InsomniaMenu menu;

    public FatigueOverview(Plugin plugin, API api, Messages texts, FatigueService fatigue, SleepService sleep,
                           RestedBonusService rested, CampfireRestService campfire) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.api = Objects.requireNonNull(api, "api");
        this.texts = Objects.requireNonNull(texts, "texts");
        this.fatigue = Objects.requireNonNull(fatigue, "fatigue");
        this.sleep = Objects.requireNonNull(sleep, "sleep");
        this.rested = Objects.requireNonNull(rested, "rested");
        this.campfire = campfire;
    }

    /** The fatigue dialog, for the "back" button. */
    public void menu(InsomniaMenu menu) { this.menu = menu; }

    /**
     * Adds the "Fatigue" entry to Core's player menu.
     */
    public void registerPlayerMenu(at.vystorm.insomnia.config.InsomniaConfig config) {
        FatiguePlayerMenu.register(plugin, api.gui().playerMenu(), config, texts.lang(), fatigue, this);
    }

    /** Most tired first; equal values by name. */
    static List<Row> order(Collection<Row> rows) {
        return rows.stream().sorted(Comparator.comparingDouble(Row::fatigue).reversed()
                .thenComparing(row -> row.name().toLowerCase(Locale.ROOT))).toList();
    }

    /** Online players whose data is loaded. */
    List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerFatigueState state = fatigue.state(player.getUniqueId()).orElse(null);
            if (state != null) rows.add(row(player, state));
        }
        return order(rows);
    }

    private Row row(Player player, PlayerFatigueState state) {
        UUID id = player.getUniqueId();
        return new Row(id, player.getName(), state.insomniaStage(), state.fatigue(), state.sleepDebt(),
                sleep.isSleeping(id), campfire != null && campfire.resting(id));
    }

    /** Opens the overview dialog for players; the console gets the same list as chat lines. */
    public void open(CommandSender viewer) {
        if (!viewer.hasPermission(ADMIN)) {
            viewer.sendMessage(texts.chat(viewer, "no_permission"));
            return;
        }
        List<Row> rows = rows();
        if (!(viewer instanceof Player admin)) {
            viewer.sendMessage(texts.chat(viewer, "overview.chat_title", "count", rows.size()));
            for (Row row : rows) viewer.sendMessage(texts.text(viewer, "overview.chat_line", "player", row.name(),
                    "stage", texts.stageName(viewer, row.stage()), "fatigue", fmt(row.fatigue()), "debt", fmt(row.debt()),
                    "state", stateLabel(viewer, row)));
            if (rows.isEmpty()) viewer.sendMessage(texts.text(viewer, "overview.empty"));
            return;
        }
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(texts.component(admin, rows.isEmpty() ? "overview.empty" : "overview.intro",
                "count", rows.size()), 300));
        if (rows.size() > MAX_ROWS) body.add(DialogBody.plainMessage(texts.component(admin, "overview.more",
                "count", rows.size() - MAX_ROWS), 300));
        List<DialogButton> buttons = new ArrayList<>();
        for (Row row : rows.subList(0, Math.min(MAX_ROWS, rows.size()))) {
            UUID target = row.id();
            buttons.add(new DialogButton(texts.component(admin, "overview.row", "player", row.name(),
                    "stage", texts.stageName(admin, row.stage()), "fatigue", fmt(row.fatigue()), "state", stateLabel(admin, row)),
                    texts.component(admin, "overview.row_hover", "debt", fmt(row.debt())),
                    (p, response) -> openPlayer(p, target)));
        }
        buttons.add(new DialogButton(texts.component(admin, "dialog.refresh"), (p, response) -> open(p)));
        if (menu != null) buttons.add(new DialogButton(texts.component(admin, "overview.back_menu"), (p, response) -> menu.open(p)));
        api.dialogs().open(plugin, admin, new DialogRequest(texts.component(admin, "overview.title"), body, List.of(), buttons,
                p -> p.isOnline() && p.hasPermission(ADMIN), () -> {}, texts.text(admin, "dialog.close"), 2));
    }

    /** Values and quick actions for one online player. */
    public void openPlayer(Player admin, UUID targetId) {
        if (!admin.hasPermission(ADMIN)) {
            admin.sendMessage(texts.chat(admin, "no_permission"));
            return;
        }
        Player target = Bukkit.getPlayer(targetId);
        PlayerFatigueState state = target == null ? null : fatigue.state(targetId).orElse(null);
        if (target == null || state == null) {
            admin.sendMessage(texts.chat(admin, "player_not_found"));
            return;
        }
        Row row = row(target, state);
        long now = System.currentTimeMillis();
        List<DialogBody> body = new ArrayList<>();
        body.add(line(admin, "dialog.state", "stage", texts.stageName(admin, row.stage())));
        body.add(line(admin, "dialog.fatigue", "bar", InsomniaMenu.bar(row.fatigue()), "value", fmt(row.fatigue())));
        body.add(line(admin, "dialog.debt", "bar", InsomniaMenu.bar(row.debt()), "value", fmt(row.debt())));
        body.add(line(admin, "dialog.rested", "rested", texts.restedLeft(admin, rested.remainingMinutes(targetId, now))));
        String status = stateLabel(admin, row);
        if (!status.isEmpty()) body.add(line(admin, "overview.activity", "state", status));

        List<DialogButton> buttons = new ArrayList<>();
        buttons.add(action(admin, "overview.action.rest", targetId, (p, t) -> {
            sleep.forceRest(t, System.currentTimeMillis());
            p.sendMessage(texts.text(p, "admin.rested", "player", t.getName()));
        }));
        buttons.add(action(admin, "overview.action.clear_debt", targetId, (p, t) -> {
            fatigue.setDebt(t.getUniqueId(), 0.0);
            p.sendMessage(texts.text(p, "admin.set_debt", "player", t.getName(), "value", fmt(fatigue.sleepDebt(t.getUniqueId()))));
        }));
        buttons.add(action(admin, "overview.action.less", targetId, (p, t) -> changeFatigue(p, t, -STEP)));
        buttons.add(action(admin, "overview.action.more", targetId, (p, t) -> changeFatigue(p, t, STEP)));
        buttons.add(new DialogButton(texts.component(admin, "overview.back"), (p, response) -> open(p)));
        api.dialogs().open(plugin, admin, new DialogRequest(texts.component(admin, "overview.player_title", "player", target.getName()),
                body, List.of(), buttons, p -> p.isOnline() && p.hasPermission(ADMIN), () -> {}, texts.text(admin, "dialog.close"), 2));
    }

    /** New effective fatigue after a quick action, clamped to 0-100. */
    static double shifted(double fatigue, double delta) {
        return Math.max(0.0, Math.min(100.0, fatigue + delta));
    }

    /**
     * Raw fatigue to store so the shown (effective) value moves by {@code delta}. The effective value is raw minus an
     * active stimulant suppression, so setting raw to the shifted effective value would change it by the suppression too.
     */
    static double rawAfterShift(PlayerFatigueState state, double delta) {
        return state.rawFatigue() + shifted(state.fatigue(), delta) - state.fatigue();
    }

    private void changeFatigue(Player admin, Player target, double delta) {
        UUID id = target.getUniqueId();
        PlayerFatigueState state = fatigue.state(id).orElse(null);
        if (state == null) return;
        fatigue.setFatigue(id, rawAfterShift(state, delta));
        admin.sendMessage(texts.text(admin, "admin.set_fatigue", "player", target.getName(), "value", fmt(fatigue.fatigue(id))));
    }

    /** A button that re-checks permission and target at click time, runs the action and reopens the player dialog. */
    private DialogButton action(Player admin, String key, UUID targetId,
                                java.util.function.BiConsumer<Player, Player> action) {
        return new DialogButton(texts.component(admin, key), (p, response) -> {
            Player target = Bukkit.getPlayer(targetId);
            if (!p.hasPermission(ADMIN)) { p.sendMessage(texts.chat(p, "no_permission")); return; }
            if (target == null || fatigue.state(targetId).isEmpty()) { p.sendMessage(texts.chat(p, "player_not_found")); return; }
            action.accept(p, target);
            openPlayer(p, targetId);
        });
    }

    /** "asleep" / "resting by a campfire" / empty. */
    private String stateLabel(CommandSender viewer, Row row) {
        if (row.sleeping()) return texts.text(viewer, "overview.sleeping");
        if (row.resting()) return texts.text(viewer, "overview.resting");
        return "";
    }

    private DialogBody line(CommandSender viewer, String key, Object... placeholders) {
        Component text = texts.component(viewer, key, placeholders);
        return DialogBody.plainMessage(text, 300);
    }

    static String fmt(double value) { return String.format(Locale.ROOT, "%.1f", value); }
}
