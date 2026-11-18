package at.vystorm.insomnia;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderNamespace;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderValue;
import Vystorm.vystorm_core.lang.Lang;
import at.vystorm.insomnia.api.InsomniaStage;
import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.model.PlayerFatigueState;
import at.vystorm.insomnia.service.FatigueService;
import at.vystorm.insomnia.service.RestedBonusService;
import at.vystorm.insomnia.service.SleepService;
import at.vystorm.insomnia.service.StimulantForecast;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The {@code sleep} placeholders (0.31.0, Vystorm Core 0.65.0): {@code %vystorm_sleep_fatigue%}, {@code {sleep:stage}} and
 * so on. Plain values; a value whose feature is switched off is empty, a player without loaded data gives null (the
 * placeholder stays as written). Fatigue data lives in server-thread maps, so no value answers off the server thread.
 */
final class InsomniaPlaceholders {
    static final String NAMESPACE = "sleep";
    static final List<String> KEYS = List.of("fatigue", "fatigue_int", "debt", "stage", "stage_name", "sleeping",
            "rested_minutes", "rested", "caffeine_seconds", "caffeine_doses");

    /** What the values read, looked up at call time. */
    interface Source {
        boolean fatigueOn();
        boolean sleepOn();
        boolean restedOn();
        boolean stimulantsOn();
        /** Null when the player's data is not loaded. */
        PlayerFatigueState state(UUID player);
        boolean sleeping(UUID player);
        long restedMinutes(UUID player, long now);
        String stageName(Player viewer, InsomniaStage stage);
    }

    private InsomniaPlaceholders() {}

    static PlaceholderNamespace register(PlaceholderInterface placeholders, Plugin owner, Lang lang, InsomniaConfig config,
                                         FatigueService fatigue, SleepService sleep, RestedBonusService rested) {
        return register(placeholders, owner, lang, new Source() {
            public boolean fatigueOn() { return config.fatigueEnabled(); }
            public boolean sleepOn() { return config.sleepEnabled(); }
            public boolean restedOn() { return config.restedEnabled(); }
            public boolean stimulantsOn() { return config.stimulantsEnabled(); }
            public PlayerFatigueState state(UUID player) { return fatigue.state(player).orElse(null); }
            public boolean sleeping(UUID player) { return sleep.isSleeping(player); }
            public long restedMinutes(UUID player, long now) { return rested.remainingMinutes(player, now); }
            public String stageName(Player viewer, InsomniaStage stage) {
                var texts = config.messages();
                return texts == null ? stage.name() : texts.stageName(viewer, stage);
            }
        });
    }

    static PlaceholderNamespace register(PlaceholderInterface placeholders, Plugin owner, Lang lang, Source s) {
        PlaceholderNamespace ns = placeholders.register(owner, NAMESPACE);
        add(ns, lang, PlaceholderValue.viewer("fatigue", p -> fatigueValue(s, p.getUniqueId(), state -> String.format(Locale.ROOT, "%.1f", state.fatigue()))));
        add(ns, lang, PlaceholderValue.viewer("fatigue_int", p -> fatigueValue(s, p.getUniqueId(), state -> Math.round(state.fatigue()))));
        add(ns, lang, PlaceholderValue.viewer("debt", p -> fatigueValue(s, p.getUniqueId(), state -> String.format(Locale.ROOT, "%.1f", state.sleepDebt()))));
        add(ns, lang, PlaceholderValue.viewer("stage", p -> fatigueValue(s, p.getUniqueId(), state -> state.insomniaStage().name())));
        add(ns, lang, PlaceholderValue.player("stage_name", p -> fatigueValue(s, p.getUniqueId(), state -> s.stageName(p, state.insomniaStage()))));
        add(ns, lang, PlaceholderValue.viewer("sleeping", p -> !s.sleepOn() ? "" : s.sleeping(p.getUniqueId())));
        add(ns, lang, PlaceholderValue.viewer("rested_minutes", p -> !s.restedOn() ? "" : s.restedMinutes(p.getUniqueId(), System.currentTimeMillis())));
        add(ns, lang, PlaceholderValue.viewer("rested", p -> !s.restedOn() ? "" : s.restedMinutes(p.getUniqueId(), System.currentTimeMillis()) > 0));
        add(ns, lang, PlaceholderValue.viewer("caffeine_seconds", p -> {
            if (!s.stimulantsOn()) return "";
            PlayerFatigueState state = s.state(p.getUniqueId());
            if (state == null) return null;
            StimulantForecast forecast = StimulantForecast.of(state, System.currentTimeMillis());
            return forecast == null ? 0 : forecast.seconds();
        }));
        add(ns, lang, PlaceholderValue.viewer("caffeine_doses", p -> {
            if (!s.stimulantsOn()) return "";
            PlayerFatigueState state = s.state(p.getUniqueId());
            return state == null ? null : state.stimulantUses();
        }));
        return ns;
    }

    private static Object fatigueValue(Source s, UUID player, java.util.function.Function<PlayerFatigueState, Object> value) {
        if (!s.fatigueOn()) return "";
        PlayerFatigueState state = s.state(player);
        return state == null ? null : value.apply(state);
    }

    private static void add(PlaceholderNamespace ns, Lang lang, PlaceholderValue value) {
        ns.add(lang == null ? value : value.description(lang.ref("placeholder." + value.key())));
    }
}
