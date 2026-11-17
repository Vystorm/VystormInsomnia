package at.vystorm.insomnia.sleep;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SleepPreferencesTest {
    /** Map-backed store, like PlayerPrefs in the player's data. */
    static final class MapStore implements SleepPreferences.Store {
        final Map<String, Object> values = new HashMap<>();
        @Override public String text(String id, String fallback) { return values.get(id) instanceof String s ? s : fallback; }
        @Override public void setText(String id, String value) { values.put(id, value); }
        @Override public double number(String id, double fallback) { return values.get(id) instanceof Double d ? d : fallback; }
        @Override public void setNumber(String id, double value) { values.put(id, value); }
    }

    @Test
    void defaultsAskAndSleepUntilMorning() {
        SleepPreferences prefs = new SleepPreferences(new MapStore());
        assertEquals(SleepPreferences.Mode.ASK, prefs.mode());
        assertEquals("morning", prefs.choice().id());
        assertEquals(SleepPreferences.DEFAULT_CUSTOM_HOUR, prefs.customHour());
    }

    @Test
    void remembersTheLastChoiceWithoutChangingTheMode() {
        MapStore store = new MapStore();
        SleepPreferences prefs = new SleepPreferences(store);
        prefs.remember(new SleepPreferences.Choice("evening", 7), false);
        assertEquals("evening", prefs.choice().id());
        assertEquals(SleepPreferences.Mode.ASK, prefs.mode());
        assertEquals("evening", store.values.get(SleepPreferences.CHOICE));
    }

    @Test
    void alwaysUseThisStopsAsking() {
        SleepPreferences prefs = new SleepPreferences(new MapStore());
        prefs.remember(new SleepPreferences.Choice(SleepPreferences.DREAM, 7), true);
        assertTrue(prefs.choice().dream());
        assertEquals(SleepPreferences.Mode.AUTO, prefs.mode());
    }

    @Test
    void customHourIsStoredAndClamped() {
        MapStore store = new MapStore();
        SleepPreferences prefs = new SleepPreferences(store);
        prefs.remember(new SleepPreferences.Choice(SleepPreferences.CUSTOM, 9), false);
        assertTrue(prefs.choice().custom());
        assertEquals(9, prefs.choice().customHour());
        store.values.put(SleepPreferences.CUSTOM_HOUR, 99.0);
        assertEquals(23, prefs.customHour());
        store.values.put(SleepPreferences.CUSTOM_HOUR, Double.NaN);
        assertEquals(SleepPreferences.DEFAULT_CUSTOM_HOUR, prefs.customHour());
    }

    @Test
    void unknownStoredValuesFallBack() {
        MapStore store = new MapStore();
        store.values.put(SleepPreferences.CHOICE, "teatime");
        store.values.put(SleepPreferences.MODE, "sometimes");
        SleepPreferences prefs = new SleepPreferences(store);
        assertEquals("morning", prefs.choice().id());
        assertEquals(SleepPreferences.Mode.ASK, prefs.mode());
        store.values.put(SleepPreferences.MODE, "CHAT");
        assertEquals(SleepPreferences.Mode.CHAT, prefs.mode());
        assertThrows(IllegalArgumentException.class, () -> prefs.remember(new SleepPreferences.Choice("teatime", 0), false));
    }

    @Test
    void choicesMatchTheSettingsOptionRules() {
        for (String id : SleepPreferences.CHOICES) assertTrue(id.matches("[A-Za-z0-9_.:-]{1,64}"), id);
        for (SleepPreferences.Mode mode : SleepPreferences.Mode.values()) assertTrue(mode.id().matches("[a-z]+"));
        for (String id : new String[]{SleepPreferences.MODE, SleepPreferences.CHOICE, SleepPreferences.CUSTOM_HOUR})
            assertTrue(id.matches("[a-z0-9_.-]{1,64}"), id);
    }

    @Test
    void adminWhoSwitchedSkippingOffIsRespected() {
        assertTrue(SleepAccelerator.skippingAllowed(100));
        assertTrue(SleepAccelerator.skippingAllowed(0));
        assertTrue(SleepAccelerator.skippingAllowed(null));
        assertFalse(SleepAccelerator.skippingAllowed(101));
    }
}
