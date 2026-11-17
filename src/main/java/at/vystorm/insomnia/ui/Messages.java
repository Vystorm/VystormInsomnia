package at.vystorm.insomnia.ui;

import Vystorm.vystorm_core.lang.Lang;
import at.vystorm.insomnia.api.InsomniaStage;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

import java.util.Locale;
import java.util.Objects;

/**
 * Player-visible texts from the Core language bundle ({@code lang/<code>.yml}, {@code api.lang()}): every viewer
 * gets their own language, admins edit {@code plugins/VystormInsomnia/lang/}. Replaces the plugin's own
 * {@code messages.yml} (up to 0.4.x; not migrated, edited texts must be copied to {@code lang/de.yml} by hand).
 */
public final class Messages {
    private final Lang lang;

    public Messages(Lang lang) {
        this.lang = Objects.requireNonNull(lang, "lang");
    }

    public Lang lang() { return lang; }

    /** Chat message with the Insomnia prefix, as legacy {@code §} string for the viewer's language. */
    public String chat(CommandSender viewer, String key, Object... placeholders) {
        return lang.text(viewer, "prefix") + lang.text(viewer, key, placeholders);
    }

    /** Text without prefix, as legacy {@code §} string. */
    public String text(CommandSender viewer, String key, Object... placeholders) {
        return lang.text(viewer, key, placeholders);
    }

    /** Text without colour codes, for labels Core colours itself. */
    public String plain(CommandSender viewer, String key, Object... placeholders) {
        return lang.plain(viewer, key, placeholders);
    }

    public Component component(CommandSender viewer, String key, Object... placeholders) {
        return lang.component(viewer, key, placeholders);
    }

    /** {@code Lang.ref} for texts Core shows to many viewers (menu entry, settings, chat button). */
    public String ref(String key) { return lang.ref(key); }

    public String stageName(CommandSender viewer, InsomniaStage stage) {
        return lang.plain(viewer, "stage_name." + stage.name().toLowerCase(Locale.ROOT));
    }

    public String hint(CommandSender viewer, InsomniaStage stage) {
        String key = stage == InsomniaStage.TIRED ? "rested" : stage.name().toLowerCase(Locale.ROOT);
        return lang.plain(viewer, "hint." + key);
    }

    /** Remaining rested bonus ({@code minutes <= 0} = none). */
    public String restedLeft(CommandSender viewer, long minutes) {
        return minutes <= 0 ? lang.plain(viewer, "rested_left.none") : lang.plain(viewer, "rested_left.minutes", "minutes", minutes);
    }

    public void reload() { lang.reload(); }
}
