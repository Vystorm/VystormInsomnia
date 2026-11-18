package at.vystorm.insomnia.ui;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._uiMethods.menu.MenuCategory;
import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.PanelSpec;
import Vystorm.vystorm_core.panel.Panels;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.Ui;
import at.vystorm.insomnia.api.InsomniaStage;
import at.vystorm.insomnia.model.PlayerFatigueState;
import at.vystorm.insomnia.service.FatigueService;
import at.vystorm.insomnia.service.RestedBonusService;
import at.vystorm.insomnia.service.SleepService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The fatigue status as a native client-mod panel (Core {@code Panels}, name {@code fatigue}, also the {@code /vmenu}
 * entry "Müdigkeit" in Charakter). Players without the mod get the existing dialog ({@link InsomniaMenu#open}) as
 * fallback. Server thread.
 */
public final class FatiguePanel {
    public static final String NAME = "fatigue";

    /** What the panel shows, computed from the player state (testable without a server). */
    public record View(InsomniaStage stage, double fatigue, double debt, long restedMinutes, long stimulantMinutes, boolean sleeping) {
        public static View of(PlayerFatigueState state, long restedMinutes, boolean sleeping, long now) {
            long stimulant = state.stimulantUntilEpochMillis() > now
                    ? Math.max(1L, (state.stimulantUntilEpochMillis() - now + 59_999L) / 60_000L) : 0L;
            return new View(state.insomniaStage(), state.fatigue(), state.sleepDebt(), restedMinutes, stimulant, sleeping);
        }
    }

    private final Plugin plugin;
    private final API api;
    private final Messages texts;
    private final FatigueService fatigue;
    private final SleepService sleep;
    private final RestedBonusService rested;
    private final Panels panels;

    public FatiguePanel(Plugin plugin, API api, Messages texts, FatigueService fatigue, SleepService sleep, RestedBonusService rested) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.api = Objects.requireNonNull(api, "api");
        this.texts = Objects.requireNonNull(texts, "texts");
        this.fatigue = Objects.requireNonNull(fatigue, "fatigue");
        this.sleep = Objects.requireNonNull(sleep, "sleep");
        this.rested = Objects.requireNonNull(rested, "rested");
        this.panels = api.gui().panels().of(plugin);
    }

    /** Registers the panel with its {@code /vmenu} entry; {@code fallback} is the dialog for players without the mod. */
    public void register(java.util.function.Consumer<Player> fallback) { register(fallback, false); }

    /** @param web also offer the web page {@link InsomniaWeb#MODULE} ("Open web page" button) */
    public void register(java.util.function.Consumer<Player> fallback, boolean web) {
        PanelSpec.Builder builder = PanelSpec.builder(texts.ref("menu.title"));
        if (web) builder.web(InsomniaWeb.MODULE, "");
        panels.register(NAME, builder
                .access(p -> enabled.getAsBoolean() && p.hasPermission("insomnia.use"))
                .menu(MenuCategory.CHARACTER, Material.RED_BED, texts.ref("menu.description"))
                .fallback(fallback)
                .render(this::render)
                .build());
    }

    public void open(Player player) { panels.open(player, NAME); }

    private java.util.function.BooleanSupplier enabled = () -> true;

    /** {@code menu.enabled} (0.31.0): while it is false the panel and its menu entry are not offered. */
    public void enabledWhen(java.util.function.BooleanSupplier enabled) { this.enabled = Objects.requireNonNull(enabled, "enabled"); }

    private Panel render(PanelContext ctx) {
        Player player = ctx.player();
        PlayerFatigueState state = fatigue.state(player.getUniqueId()).orElse(null);
        Panel page = Panel.page(ctx.text("dialog.title")).size(Panel.Size.SMALL).icon(Ui.item(Material.RED_BED));
        if (state == null) return page.children(Ui.text(ctx.text("panel.loading")).style(Ui.TextStyle.MUTED));
        long now = System.currentTimeMillis();
        View view = View.of(state, rested.remainingMinutes(player.getUniqueId(), now), sleep.isSleeping(player.getUniqueId()), now);
        page.children(elements(view, ctx, texts.stageName(player, view.stage()), texts.hint(player, view.stage()),
                texts.restedLeft(player, view.restedMinutes())).toArray(Ui.Element<?>[]::new));
        return page.footer(footer(ctx, player.hasPermission(InsomniaMenu.ADMIN) ? InsomniaMenu.settingsKey(plugin) : null));
    }

    /** Footer: refresh; for admins Core's settings panel on the Insomnia section ({@code Button.openSettings}, 0.26.4). */
    static Ui.Button[] footer(PanelContext ctx, String settingsKey) {
        List<Ui.Button> footer = new ArrayList<>();
        footer.add(Ui.button("refresh").label(ctx.text("dialog.refresh")).onClick(a -> { a.refresh(); return Result.ok(); }));
        if (settingsKey != null) footer.add(Ui.button("settings").label(ctx.text("panel.settings")).ghost().openSettings(settingsKey));
        return footer.toArray(Ui.Button[]::new);
    }

    /** Body elements for a view; ids follow meaning (state, fatigue, debt, rested, stimulant, sleeping, hint). */
    static List<Ui.Element<?>> elements(View view, PanelContext ctx, String stageName, String hint, String restedLeft) {
        List<Ui.Element<?>> out = new ArrayList<>();
        out.add(Ui.kv().id("state").row(ctx.text("panel.state"), stageName).row(ctx.text("panel.rested"), restedLeft));
        out.add(Ui.progress(view.fatigue(), 100).id("fatigue").color(FatigueHud.color(view.stage())).segments(5)
                .label(ctx.text("panel.fatigue", "value", fmt(view.fatigue()))));
        out.add(Ui.progress(view.debt(), 100).id("debt").color("#FF7E57C2")
                .label(ctx.text("panel.debt", "value", fmt(view.debt()))));
        if (view.stimulantMinutes() > 0)
            out.add(Ui.text(ctx.text("panel.stimulant", "minutes", view.stimulantMinutes())).id("stimulant").style(Ui.TextStyle.WARN));
        if (view.sleeping()) out.add(Ui.text(ctx.text("panel.sleeping")).id("sleeping").style(Ui.TextStyle.OK));
        out.add(Ui.text(hint).id("hint").style(Ui.TextStyle.MUTED));
        return out;
    }

    static String fmt(double value) { return String.format(Locale.ROOT, "%.1f", value); }
}
