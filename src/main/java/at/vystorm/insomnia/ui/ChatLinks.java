package at.vystorm.insomnia.ui;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Clickable chat lines via Core {@code ChatActions}. Players without {@code insomnia.use} get the plain text.
 */
public final class ChatLinks {
    public static final String MENU_KEY = "vystorminsomnia:fatigue";

    private ChatLinks() {}

    /**
     * Text followed by a button opening the fatigue dialog (if the player may open it).
     *
     * @param label button label, may be a {@code Lang.ref}
     */
    public static void withMenu(Vystorm.vystorm_core.API api, Player player, String legacy, String label) {
        if (!player.hasPermission("insomnia.use")) { api.compat().platform().sendMessage(player, legacy); return; }
        Actions.withMenu(api, player, legacy, label);
    }

    private static final class Actions {
        static void withMenu(Vystorm.vystorm_core.API api, Player player, String legacy, String label) {
            var chat = api.message().chat();
            api.compat().platform().sendMessage(player, chat.text(legacy).append(Component.space()).append(chat.menu(label, MENU_KEY)));
        }
    }
}
