package at.vystorm.insomnia;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderTesting;
import at.vystorm.insomnia.api.InsomniaStage;
import at.vystorm.insomnia.model.PlayerFatigueState;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** The sleep placeholders resolve through Core's real parser, and a switched-off feature gives an empty value. */
class InsomniaPlaceholdersTest {
    private static final class Fake implements InsomniaPlaceholders.Source {
        boolean fatigue = true, sleep = true, rested = true, stimulants = true, loaded = true;
        PlayerFatigueState state;
        public boolean fatigueOn() { return fatigue; }
        public boolean sleepOn() { return sleep; }
        public boolean restedOn() { return rested; }
        public boolean stimulantsOn() { return stimulants; }
        public PlayerFatigueState state(UUID player) {
            if (!loaded) return null;
            if (state == null) { state = new PlayerFatigueState(player, 1000); state.fatigue(42.25); state.sleepDebt(10); }
            return state;
        }
        public boolean sleeping(UUID player) { return true; }
        public long restedMinutes(UUID player, long now) { return 12; }
        public String stageName(Player viewer, InsomniaStage stage) { return stage.name().toLowerCase(); }
    }

    private final PlaceholderTesting testing = PlaceholderTesting.detached();
    private final Plugin owner = PlaceholderTesting.plugin("VystormInsomnia");
    private final OfflinePlayer alex = PlaceholderTesting.viewer("Alex");
    private final Fake source = new Fake();

    @Test void givenRegisteredValues_whenResolved_thenPlainText() {
        InsomniaPlaceholders.register(testing.placeholders(), owner, null, source);
        assertEquals("42.3 42 10.0", testing.resolve(alex, "%vystorm_sleep_fatigue% {sleep:fatigue_int} {sleep:debt}"));
        assertEquals(source.state.insomniaStage().name(), testing.resolve(alex, "{sleep:stage}"));
        assertEquals("true 12 true 0 0", testing.resolve(alex, "{sleep:sleeping} {sleep:rested_minutes} {sleep:rested} {sleep:caffeine_seconds} {sleep:caffeine_doses}"));
        assertEquals(InsomniaPlaceholders.KEYS.size(), testing.placeholders().namespace("sleep").orElseThrow().values().size());
    }

    @Test void givenFeaturesOff_whenResolved_thenEmpty() {
        InsomniaPlaceholders.register(testing.placeholders(), owner, null, source);
        source.fatigue = false;
        source.sleep = false;
        source.rested = false;
        source.stimulants = false;
        assertEquals("[][][][][][][][]", testing.resolve(alex,
                "[{sleep:fatigue}][{sleep:debt}][{sleep:stage}][{sleep:sleeping}][{sleep:rested_minutes}][{sleep:rested}][{sleep:caffeine_seconds}][{sleep:caffeine_doses}]"));
    }

    @Test void givenNoDataOrOtherThread_whenResolved_thenPlaceholderStays() {
        InsomniaPlaceholders.register(testing.placeholders(), owner, null, source);
        assertEquals("{sleep:fatigue}", testing.resolveOffThread(alex, "{sleep:fatigue}"), "server-thread data only");
        source.loaded = false;
        assertEquals("{sleep:fatigue}", testing.resolve(alex, "{sleep:fatigue}"), "data not loaded yet");
    }
}
