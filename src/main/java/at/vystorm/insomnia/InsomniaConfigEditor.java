package at.vystorm.insomnia;

import Vystorm.vystorm_core._uiMethods.settings.ConfigEditorInterface;
import Vystorm.vystorm_core._uiMethods.settings.SettingPolicy;
import Vystorm.vystorm_core.lang.Lang;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * Makes {@code config.yml}, {@code stimulants.yml} and {@code mobs.yml} editable in Core's admin config editor
 * ({@code config-editor}); the config menu is added on Core 0.54.0+ ({@code config-menu}). Labels reuse the settings-section texts ({@code setting.<path>.label|desc}).
 */
final class InsomniaConfigEditor {
    /** A labelled key of config.yml; {@code min} is NaN when the key has no slider range. */
    record Entry(String path, double min, double max, double step) {
        boolean ranged() { return !Double.isNaN(min); }
        String labelKey() { return "setting." + path + ".label"; }
        String descriptionKey() { return "setting." + path + ".desc"; }
    }

    static final List<Entry> ENTRIES = List.of(
            label("fatigue.enabled"),
            label("debt_reminder.enabled"),
            range("debt_reminder.threshold", 1.0, 100.0, 1.0),
            range("debt_reminder.interval_minutes", 1, 1440, 1),
            range("fatigue.base_per_minute", 0.0, 1.0, 0.0025),
            range("fatigue.debt_per_minute", 0.0, 1.0, 0.0025),
            range("sleep.fatigue_recovery_per_second", 0.0, 5.0, 0.05),
            range("sleep.debt_recovery_per_second", 0.0, 5.0, 0.05),
            label("sleep.cancel_vanilla_night_skip"),
            label("sleep.dialog.enabled"),
            label("sleep.dialog.day_naps"),
            label("sleep.dialog.custom_time"),
            label("sleep.acceleration.enabled"),
            range("sleep.acceleration.min_speed", 1.0, 100.0, 0.5),
            range("sleep.acceleration.max_speed", 1.0, 100.0, 0.5),
            range("sleep.acceleration.curve", 0.1, 5.0, 0.1),
            label("sleep.acceleration.action_bar"),
            label("sleep.wake_summary"),
            label("stimulants.enabled"),
            label("hallucinations.enabled"),
            range("hallucinations.chance_exhausted", 0.0, 1.0, 0.001),
            range("hallucinations.chance_overmuedet", 0.0, 1.0, 0.001),
            range("hallucinations.chance_sleep_deprivation", 0.0, 1.0, 0.001),
            label("microsleep.enabled"),
            range("microsleep.min_fatigue", 0.0, 100.0, 0.5),
            range("microsleep.chance_per_check", 0.0, 1.0, 0.001),
            range("microsleep.cooldown_seconds", 10, 1800, 1),
            label("insomnia_mobs.enabled"),
            range("insomnia_mobs.min_fatigue", 0.0, 100.0, 0.5),
            range("insomnia_mobs.base_spawn_chance", 0.0, 1.0, 0.001),
            label("rested.enabled"),
            range("rested.duration_minutes", 5, 240, 1),
            range("rested.xp_multiplier", 1.0, 2.0, 0.01),
            label("rest.campfire.enabled"),
            range("rest.campfire.fatigue_per_second", 0.0, 2.0, 0.05),
            range("rest.campfire.min_fatigue", 0.0, 100.0, 1.0),
            label("player_menu.enabled"),
            label("player_menu.show_stage_to_players"),
            label("integrations.cityclaim"),
            label("integrations.bloodmoon"));

    private InsomniaConfigEditor() {}

    private static Entry range(String path, double min, double max, double step) { return new Entry(path, min, max, step); }
    private static Entry label(String path) { return new Entry(path, Double.NaN, Double.NaN, Double.NaN); }

    /**
     * {@code reload} is {@code /insomnia reload}: all three files are loaded and validated; an invalid change is
     * rejected (the previous configuration stays active) and the admin gets the reason.
     */
    static void register(Plugin plugin, ConfigEditorInterface editor, Lang lang, Runnable reload) {
        editor.register(plugin, "config.yml", reload);
        editor.register(plugin, "stimulants.yml", reload);
        editor.register(plugin, "mobs.yml", reload);
        editor.registerAll(plugin, reload);
        for (Entry entry : ENTRIES) {
            editor.label(plugin, entry.path(), lang.ref(entry.labelKey()), lang.ref(entry.descriptionKey()));
            if (entry.ranged()) editor.range(plugin, entry.path(), entry.min(), entry.max(), entry.step());
        }
        editor.hidden(plugin, "config_version");
        editor.activation(plugin, "storage", SettingPolicy.Activation.RESTART);
    }
}
