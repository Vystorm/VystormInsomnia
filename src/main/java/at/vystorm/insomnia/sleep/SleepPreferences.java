package at.vystorm.insomnia.sleep;

import java.util.List;
import java.util.Locale;

/**
 * Per-player sleep choices (stored in Core {@code PlayerPrefs}, editable in {@code /vsettings}).
 * <ul>
 *   <li>{@value #MODE}: {@code ask} (dialog every time), {@code auto} (use the saved choice without asking),
 *       {@code chat} (ask with clickable chat instead of a dialog)</li>
 *   <li>{@value #CHOICE}: the last choice, a preset id, {@code custom} or {@code dream}</li>
 *   <li>{@value #CUSTOM_HOUR}: wake-up hour 0-23 for {@code custom}</li>
 * </ul>
 */
public final class SleepPreferences {
    public static final String MODE = "sleep_dialog";
    public static final String CHOICE = "sleep_choice";
    public static final String CUSTOM_HOUR = "sleep_custom_hour";

    public static final String DREAM = "dream";
    public static final String CUSTOM = "custom";
    public static final List<String> PRESETS = List.of("morning", "noon", "evening", "night");
    public static final List<String> CHOICES = List.of("morning", "noon", "evening", "night", CUSTOM, DREAM);
    public static final String DEFAULT_CHOICE = "morning";
    public static final int DEFAULT_CUSTOM_HOUR = 7;

    public enum Mode {
        ASK, AUTO, CHAT;

        public String id() { return name().toLowerCase(Locale.ROOT); }

        static Mode parse(String value) {
            if (value != null) for (Mode mode : values()) if (mode.id().equalsIgnoreCase(value.trim())) return mode;
            return ASK;
        }
    }

    /** What the player picked: a preset or own hour (with its clock ticks), or the dream realm. */
    public record Choice(String id, int customHour) {
        public boolean dream() { return DREAM.equals(id); }
        public boolean custom() { return CUSTOM.equals(id); }
    }

    /** The storage behind the preferences; {@code PlayerPrefs} on the server, a map in tests. */
    public interface Store {
        String text(String id, String fallback);
        void setText(String id, String value);
        double number(String id, double fallback);
        void setNumber(String id, double value);
    }

    private final Store store;

    public SleepPreferences(Store store) { this.store = store; }

    public Mode mode() { return Mode.parse(store.text(MODE, Mode.ASK.id())); }

    public void mode(Mode mode) { store.setText(MODE, mode.id()); }

    /** The saved choice; unknown values fall back to the morning. */
    public Choice choice() {
        String id = store.text(CHOICE, DEFAULT_CHOICE);
        String normalized = id == null ? DEFAULT_CHOICE : id.trim().toLowerCase(Locale.ROOT);
        if (!CHOICES.contains(normalized)) normalized = DEFAULT_CHOICE;
        return new Choice(normalized, customHour());
    }

    public int customHour() {
        double raw = store.number(CUSTOM_HOUR, DEFAULT_CUSTOM_HOUR);
        if (!Double.isFinite(raw)) return DEFAULT_CUSTOM_HOUR;
        return (int) Math.max(0, Math.min(23, Math.round(raw)));
    }

    /** Remembers a choice; {@code always} also stops the dialog from asking next time. */
    public void remember(Choice choice, boolean always) {
        if (!CHOICES.contains(choice.id())) throw new IllegalArgumentException("unknown sleep choice " + choice.id());
        store.setText(CHOICE, choice.id());
        if (choice.custom()) store.setNumber(CUSTOM_HOUR, Math.max(0, Math.min(23, choice.customHour())));
        if (always) mode(Mode.AUTO);
    }
}
