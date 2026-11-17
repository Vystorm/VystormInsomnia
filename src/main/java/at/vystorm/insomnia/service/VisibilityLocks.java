package at.vystorm.insomnia.service;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bukkit's hidePlayer/showPlayer are keyed by plugin, so two Insomnia effects hiding the same player would undo
 * each other. This counts the reasons per viewer and target: the target becomes visible again only when the last
 * reason is released.
 */
public final class VisibilityLocks {
    private final Plugin plugin;
    private final Map<UUID, Map<UUID, Integer>> locks = new HashMap<>();

    public VisibilityLocks(Plugin plugin) {
        this.plugin = plugin;
    }

    public void hide(Player viewer, Player target) {
        int count = locks.computeIfAbsent(viewer.getUniqueId(), ignored -> new HashMap<>())
                .merge(target.getUniqueId(), 1, Integer::sum);
        if (count == 1) viewer.hidePlayer(plugin, target);
    }

    /** Releases one reason; no-op if this viewer does not hide the target. */
    public void show(Player viewer, Player target) {
        Map<UUID, Integer> hidden = locks.get(viewer.getUniqueId());
        if (hidden == null) return;
        Integer count = hidden.get(target.getUniqueId());
        if (count == null) return;
        if (count > 1) {
            hidden.put(target.getUniqueId(), count - 1);
            return;
        }
        hidden.remove(target.getUniqueId());
        if (hidden.isEmpty()) locks.remove(viewer.getUniqueId());
        viewer.showPlayer(plugin, target);
    }

    public boolean hidden(Player viewer, Player target) {
        Map<UUID, Integer> hidden = locks.get(viewer.getUniqueId());
        return hidden != null && hidden.containsKey(target.getUniqueId());
    }

    /**
     * Called on quit: viewers still hiding the leaving player show them again (the hide would otherwise outlive a
     * relog), and everything the leaving player hid is dropped with their Player object.
     */
    public void forget(Player leaving, Iterable<? extends Player> online) {
        locks.remove(leaving.getUniqueId());
        for (Player viewer : online) {
            Map<UUID, Integer> hidden = locks.get(viewer.getUniqueId());
            if (hidden == null || hidden.remove(leaving.getUniqueId()) == null) continue;
            if (hidden.isEmpty()) locks.remove(viewer.getUniqueId());
            viewer.showPlayer(plugin, leaving);
        }
    }

    public void clear() {
        locks.clear();
    }
}
