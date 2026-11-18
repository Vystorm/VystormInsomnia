package at.vystorm.insomnia.ui;

import Vystorm.vystorm_core.web.ClientElement;
import Vystorm.vystorm_core.web.ClientServices;
import Vystorm.vystorm_core.web.ClientUi;
import Vystorm.vystorm_core.web.VystormClientReadyEvent;
import Vystorm.vystorm_core.web.VystormClientStateEvent;
import at.vystorm.insomnia.api.InsomniaStage;
import at.vystorm.insomnia.model.PlayerFatigueState;
import at.vystorm.insomnia.service.FatigueService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Client-mod HUD (Core {@code ClientUi}): a fatigue bar and a sleep-debt bar at the top right, coloured by stage,
 * plus a toast on every stage change. Players without the mod (or with the UI hidden by H) keep the vanilla path:
 * no bars, stage changes as chat messages. Updates are sent only when a rounded value or the stage changed.
 * Server thread only; load only when Core has {@code web-client-ui}.
 */
public final class FatigueHud implements Listener {
    static final String FATIGUE = "fatigue";
    static final String DEBT = "debt";
    static final String RESTED = "rested";

    private final ClientUi ui;
    private final Messages texts;
    private final FatigueService fatigue;
    private final Map<UUID, String> sent = new HashMap<>();
    /** Rested-bonus end sent per player (the client counts the bar down itself). */
    private final Map<UUID, Long> restedSent = new HashMap<>();
    private final java.util.function.LongSupplier restedTotal;

    public FatigueHud(Plugin plugin, Vystorm.vystorm_core.web.ClientUiInterface clientUi, Messages texts, FatigueService fatigue) { this(plugin, clientUi, texts, fatigue, () -> 45L * 60_000L); }

    /** @param restedTotal full rested-bonus duration in ms (the countdown bar's maximum) */
    public FatigueHud(Plugin plugin, Vystorm.vystorm_core.web.ClientUiInterface clientUi, Messages texts, FatigueService fatigue, java.util.function.LongSupplier restedTotal) {
        this.restedTotal = restedTotal;
        this.ui = clientUi.of(Objects.requireNonNull(plugin, "plugin"));
        this.texts = Objects.requireNonNull(texts, "texts");
        this.fatigue = Objects.requireNonNull(fatigue, "fatigue");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onReady(VystormClientReadyEvent event) {
        sent.remove(event.getPlayer().getUniqueId());
        restedSent.remove(event.getPlayer().getUniqueId());
        update(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onState(VystormClientStateEvent event) {
        if (!event.uiHiddenChanged()) return;
        sent.remove(event.getPlayer().getUniqueId());
        update(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        sent.remove(event.getPlayer().getUniqueId());
        restedSent.remove(event.getPlayer().getUniqueId());
    }

    /** Every second from the runtime tick; sends only changes. */
    public void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) update(player);
    }

    private java.util.function.BooleanSupplier enabled = () -> true;

    /** {@code client_hud.enabled} (0.31.0): while it is false no bar or toast is sent and bars already shown are removed. */
    public void enabledWhen(java.util.function.BooleanSupplier enabled) { this.enabled = Objects.requireNonNull(enabled, "enabled"); }

    void update(Player player) {
        if (!enabled.getAsBoolean()) {
            if (sent.remove(player.getUniqueId()) != null | restedSent.remove(player.getUniqueId()) != null) {
                ui.remove(player, FATIGUE);
                ui.remove(player, DEBT);
                ui.remove(player, RESTED);
            }
            return;
        }
        if (!ui.available(player, ClientServices.CAP_HUD)) { sent.remove(player.getUniqueId()); return; }
        PlayerFatigueState state = fatigue.state(player.getUniqueId()).orElse(null);
        if (state == null) return;
        updateRested(player, state.restedUntilEpochMillis(), System.currentTimeMillis());
        long value = Math.round(state.fatigue());
        long debt = Math.round(state.sleepDebt());
        InsomniaStage stage = state.insomniaStage();
        String signature = value + "/" + debt + "/" + stage;
        if (signature.equals(sent.get(player.getUniqueId())) && ui.has(player, FATIGUE)) return;
        sent.put(player.getUniqueId(), signature);
        String fatigueLabel = texts.lang().plain(player, "hud.fatigue", "value", value, "stage", texts.stageName(player, stage));
        String debtLabel = texts.lang().plain(player, "hud.debt", "value", debt);
        if (!ui.patch(player, ClientElement.bar(FATIGUE).value(value).color(color(stage)).label(fatigueLabel)))
            ui.put(player, fatigueBar(value, stage, fatigueLabel));
        if (!ui.patch(player, ClientElement.bar(DEBT).value(debt).label(debtLabel)))
            ui.put(player, debtBar(debt, debtLabel));
    }

    /**
     * Rested bonus as a bar that counts down on the client ({@code Bar.countdown} + {@code {time}} label, client 0.9.0+):
     * sent once per bonus, removed when it ends. Older clients get no bar (the status panel shows the minutes).
     */
    private void updateRested(Player player, long restedUntil, long now) {
        Long sentUntil = restedSent.get(player.getUniqueId());
        if (restedUntil <= now) {
            if (sentUntil != null) { restedSent.remove(player.getUniqueId()); ui.remove(player, RESTED); }
            return;
        }
        if (sentUntil != null && sentUntil == restedUntil && ui.has(player, RESTED)) return;
        if (ui.put(player, restedBar(restedUntil - now, restedTotal.getAsLong(), texts.lang().plain(player, "hud.rested"))))
            restedSent.put(player.getUniqueId(), restedUntil);
        else restedSent.remove(player.getUniqueId());
    }

    /**
     * Toast for a stage change. Returns {@code true} if it was shown, so the chat message can be skipped; without the
     * mod, without toasts or with the UI hidden it returns {@code false} and the chat message stays.
     */
    public boolean stageChanged(Player player, InsomniaStage stage, String messageKey) {
        if (!enabled.getAsBoolean() || !ui.available(player, ClientServices.CAP_TOASTS) || !ui.visible(player)) return false;
        boolean shown = ui.toast(player, stageToast(texts.stageName(player, stage), texts.lang().plain(player, messageKey), stage));
        if (shown) update(player);
        return shown;
    }

    static ClientElement.Bar fatigueBar(double value, InsomniaStage stage, String label) {
        return ClientElement.bar(FATIGUE).anchor(ClientElement.Anchor.TOP_RIGHT).offset(4, 4).order(20).stack(true).width(120).height(9)
                .value(value).max(100).color(color(stage)).background("#B0200A33").textColor("#FFFFFFFF").label(label).segments(5);
    }

    static ClientElement.Bar debtBar(double value, String label) {
        return ClientElement.bar(DEBT).anchor(ClientElement.Anchor.TOP_RIGHT).offset(4, 15).order(21).stack(true).width(120).height(7)
                .value(value).max(100).color("#FF7E57C2").background("#B0200A33").textColor("#FFFFFFFF").label(label);
    }

    /** Countdown bar for the rested bonus; the label's {@code {time}} is filled and counted by the client. */
    static ClientElement.Bar restedBar(long remainingMs, long totalMs, String label) {
        return ClientElement.bar(RESTED).anchor(ClientElement.Anchor.TOP_RIGHT).offset(4, 24).order(22).stack(true).width(120).height(7)
                .countdown(remainingMs, Math.max(remainingMs, totalMs)).color("#FF55FF55").background("#B0200A33").textColor("#FFFFFFFF")
                .label(label + " " + ClientElement.Bar.TIME);
    }

    static ClientElement.Toast stageToast(String title, String text, InsomniaStage stage) {
        ClientElement.Toast.Style style = switch (stage) {
            case RESTED -> ClientElement.Toast.Style.SUCCESS;
            case TIRED -> ClientElement.Toast.Style.INFO;
            case EXHAUSTED -> ClientElement.Toast.Style.WARNING;
            case OVERMUEDET, SLEEP_DEPRIVATION -> ClientElement.Toast.Style.ERROR;
        };
        return ClientElement.toast(ClientElement.Text.of(title, color(stage)).bold()).text(text)
                .icon(ClientElement.Icon.item("minecraft:red_bed")).style(style).duration(6000);
    }

    /** Stage colour as {@code #AARRGGBB}. */
    static String color(InsomniaStage stage) {
        return switch (stage) {
            case RESTED -> "#FF55FF55";
            case TIRED -> "#FFFFFF55";
            case EXHAUSTED -> "#FFFFAA00";
            case OVERMUEDET -> "#FFFF5555";
            case SLEEP_DEPRIVATION -> "#FFAA0000";
        };
    }
}
