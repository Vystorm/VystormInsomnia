package at.vystorm.insomnia.ui;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.playermenu.PlayerMenu;
import Vystorm.vystorm_core.playermenu.PlayerMenuEntry;
import at.vystorm.insomnia.api.InsomniaStage;
import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.model.PlayerFatigueState;
import at.vystorm.insomnia.service.FatigueService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * "Fatigue" entry in Core's player menu (sneak + right-click a player, Core 0.43.0 {@code player-menu}). Admins see
 * the values and open {@link FatigueOverview#openPlayer} with one click; everyone else only sees how tired the other
 * player looks ({@code player_menu.show_stage_to_players}).
 */
final class FatiguePlayerMenu {
    static final String ID = "fatigue";

    private FatiguePlayerMenu() {}

    /** Whether {@code viewer} sees the entry about {@code target}. */
    static boolean visible(boolean enabled, boolean showStage, boolean viewerUses, boolean viewerAdmin, boolean targetLoaded) {
        if (!enabled || !targetLoaded) return false;
        return viewerAdmin || (showStage && viewerUses);
    }

    static void register(Plugin plugin, Vystorm.vystorm_core.playermenu.PlayerMenuInterface playerMenu, InsomniaConfig config, Lang lang, FatigueService fatigue, FatigueOverview overview) {
        playerMenu.register(plugin, PlayerMenuEntry.builder(ID)
                .priority(220)
                .visibleWhen((viewer, target) -> visible(config.playerMenuEnabled(), config.playerMenuShowsStage(),
                        viewer.hasPermission("insomnia.use"), viewer.hasPermission(FatigueOverview.ADMIN),
                        fatigue.state(target.getUniqueId()).isPresent()))
                .icon((viewer, target) -> icon(lang, fatigue, viewer, target))
                .onClick((viewer, target, click) -> {
                    if (viewer.hasPermission(FatigueOverview.ADMIN)) overview.openPlayer(viewer, target.getUniqueId());
                    return PlayerMenu.Result.KEEP;
                })
                .build());
    }

    private static ItemStack icon(Lang lang, FatigueService fatigue, Player viewer, Player target) {
        PlayerFatigueState state = fatigue.state(target.getUniqueId()).orElse(null);
        if (state == null) return null;
        InsomniaStage stage = state.insomniaStage();
        String stageName = lang.plain(viewer, "stage_name." + stage.name().toLowerCase(java.util.Locale.ROOT));
        ItemStack item = new ItemStack(material(stage));
        if (viewer.hasPermission(FatigueOverview.ADMIN)) {
            lang.name(item, viewer, "player_menu.admin_name", "player", target.getName());
            return lang.lore(item, viewer, "player_menu.admin_lore", "stage", stageName,
                    "fatigue", FatigueOverview.fmt(state.fatigue()), "debt", FatigueOverview.fmt(state.sleepDebt()));
        }
        lang.name(item, viewer, "player_menu.name", "player", target.getName(), "stage", stageName);
        return lang.lore(item, viewer, "player_menu.lore." + stage.name().toLowerCase(java.util.Locale.ROOT));
    }

    /** Bed colour by stage, so the state is visible at a glance. */
    static Material material(InsomniaStage stage) {
        return switch (stage) {
            case RESTED -> Material.LIME_BED;
            case TIRED -> Material.YELLOW_BED;
            case EXHAUSTED -> Material.ORANGE_BED;
            case OVERMUEDET -> Material.RED_BED;
            case SLEEP_DEPRIVATION -> Material.BLACK_BED;
        };
    }
}
