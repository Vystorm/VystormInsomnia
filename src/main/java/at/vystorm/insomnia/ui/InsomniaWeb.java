package at.vystorm.insomnia.ui;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.web.WebModule;
import Vystorm.vystorm_core.web.WebOpenResult;
import Vystorm.vystorm_core.web.WebResponse;
import Vystorm.vystorm_core.web.WebRoute;
import Vystorm.vystorm_core.web.WebServices;
import at.vystorm.insomnia.api.InsomniaStage;
import at.vystorm.insomnia.model.PlayerFatigueState;
import at.vystorm.insomnia.service.FatigueService;
import at.vystorm.insomnia.service.RestedBonusService;
import at.vystorm.insomnia.service.SleepService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Web page {@code insomnia} in Core's web UI ({@code /app/insomnia}, also the client overlay): live fatigue status
 * (stage, fatigue, sleep debt, rested bonus, stimulant, sleep, hint, stage thresholds) and the fatigue history of the
 * current session (one sample per minute, kept in memory while the player is online, at most {@link #HISTORY} samples).
 * Read-only: sleeping and stimulants stay in-game actions. Only loaded when Core has the {@code web-live} capability.
 */
public final class InsomniaWeb implements org.bukkit.event.Listener {
    public static final String MODULE = "insomnia";
    static final int HISTORY = 180;
    static final long SAMPLE_MILLIS = 60_000L;

    /** One history point (epoch millis, fatigue, debt). */
    record Sample(long at, double fatigue, double debt) {}

    private final Plugin plugin;
    private final Messages texts;
    private final Vystorm.vystorm_core.web.WebInterface webServices;
    private final FatigueService fatigue;
    private final SleepService sleep;
    private final RestedBonusService rested;
    private final java.util.function.Supplier<List<Double>> thresholds;
    private final Map<UUID, Deque<Sample>> history = new ConcurrentHashMap<>();
    private final Map<UUID, String> pushed = new ConcurrentHashMap<>();

    public InsomniaWeb(Plugin plugin, Vystorm.vystorm_core.web.WebInterface webServices, Messages texts, FatigueService fatigue, SleepService sleep, RestedBonusService rested,
                       java.util.function.Supplier<List<Double>> thresholds) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.webServices = webServices;
        this.texts = Objects.requireNonNull(texts, "texts");
        this.fatigue = Objects.requireNonNull(fatigue, "fatigue");
        this.sleep = Objects.requireNonNull(sleep, "sleep");
        this.rested = Objects.requireNonNull(rested, "rested");
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
    }

    public void register() {
        webServices.register(plugin, WebModule.builder(MODULE, texts.ref("web.module.title"))
                .description(texts.ref("web.module.description"))
                .permission("insomnia.use")
                .snapshot(ctx -> Map.of("status", ctx.sync(() -> status(ctx.playerId(), ctx.language())),
                        "history", history(ctx.playerId())))
                .route(WebRoute.get("/status", r -> WebResponse.json(r.sync(() -> status(r.playerId(), r.language())))).rateLimit(120).build())
                .route(WebRoute.get("/history", r -> WebResponse.json(history(r.playerId()))).rateLimit(60).build())
                .build());
    }

    /** Overlay (client mod) or chat link to the page; {@code fallback} when the web platform is off. Server thread. */
    public WebOpenResult open(Player player, Runnable fallback) {
        return webServices.openOrLink(player, MODULE, "", fallback);
    }

    public boolean available() {
        try { return webServices.available() && webServices.registered(MODULE); }
        catch (RuntimeException off) { return false; }
    }

    /** Every second (runtime tick, server thread): pushes changed status, records one sample per minute. */
    public void tick(long now) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerFatigueState state = fatigue.state(player.getUniqueId()).orElse(null);
            if (state == null) continue;
            record(player.getUniqueId(), new Sample(now, state.fatigue(), state.sleepDebt()));
            String signature = Math.round(state.fatigue() * 10) + "/" + Math.round(state.sleepDebt() * 10) + "/" + state.insomniaStage()
                    + "/" + rested.remainingMinutes(player.getUniqueId(), now) + "/" + sleep.isSleeping(player.getUniqueId());
            if (signature.equals(pushed.get(player.getUniqueId()))) continue;
            pushed.put(player.getUniqueId(), signature);
            try {
                if (webServices.push(player.getUniqueId(), MODULE, "status", status(player.getUniqueId(), texts.lang().language(player)))) {
                    webServices.push(player.getUniqueId(), MODULE, "history", history(player.getUniqueId()));
                }
            } catch (RuntimeException off) {
                // web off: nothing to update
            }
        }
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) { forget(event.getPlayer().getUniqueId()); }

    /** Player left: the session history ends with them. */
    public void forget(UUID player) {
        history.remove(player);
        pushed.remove(player);
    }

    void record(UUID player, Sample sample) {
        Deque<Sample> samples = history.computeIfAbsent(player, id -> new ArrayDeque<>());
        synchronized (samples) {
            Sample last = samples.peekLast();
            if (last != null && sample.at() - last.at() < SAMPLE_MILLIS) return;
            samples.addLast(sample);
            while (samples.size() > HISTORY) samples.removeFirst();
        }
    }

    JsonObject history(UUID player) {
        JsonObject json = new JsonObject();
        JsonArray points = new JsonArray();
        Deque<Sample> samples = history.get(player);
        if (samples != null) synchronized (samples) {
            for (Sample s : samples) {
                JsonArray point = new JsonArray();
                point.add(s.at());
                point.add(round(s.fatigue()));
                point.add(round(s.debt()));
                points.add(point);
            }
        }
        json.addProperty("intervalSeconds", SAMPLE_MILLIS / 1000);
        json.add("points", points);
        return json;
    }

    /** Status JSON (server thread). */
    JsonObject status(UUID player, String language) {
        Lang lang = texts.lang();
        PlayerFatigueState state = fatigue.state(player).orElse(null);
        long now = System.currentTimeMillis();
        JsonObject json = new JsonObject();
        json.addProperty("online", Bukkit.getPlayer(player) != null);
        json.addProperty("loaded", state != null);
        JsonArray stages = new JsonArray();
        for (double threshold : thresholds.get()) stages.add(threshold);
        json.add("thresholds", stages);
        if (state == null) return json;
        InsomniaStage stage = state.insomniaStage();
        String stageKey = stage.name().toLowerCase(Locale.ROOT);
        json.addProperty("stage", stageKey);
        json.addProperty("stageName", lang.plain(language, "stage_name." + stageKey));
        json.addProperty("fatigue", round(state.fatigue()));
        json.addProperty("debt", round(state.sleepDebt()));
        long restedMinutes = rested.remainingMinutes(player, now);
        json.addProperty("restedMinutes", restedMinutes);
        long stimulant = state.stimulantUntilEpochMillis() > now ? Math.max(1L, (state.stimulantUntilEpochMillis() - now + 59_999L) / 60_000L) : 0L;
        json.addProperty("stimulantMinutes", stimulant);
        json.addProperty("sleeping", sleep.isSleeping(player));
        json.addProperty("hint", lang.plain(language, "hint." + (stage == InsomniaStage.TIRED ? "rested" : stageKey)));
        return json;
    }

    static double round(double value) { return Math.round(value * 10.0) / 10.0; }
}
