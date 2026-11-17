package at.vystorm.insomnia.command;

import at.vystorm.insomnia.dream.DreamService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Small dedicated command surface exposed to visitors by Core's VisitPolicy: {@code /dream}, {@code /wake} and
 * {@code /dreamloot}. Registered through Core's command system ({@link InsomniaCommands}).
 */
public final class DreamCommand implements CommandExecutor, TabCompleter {
    private final DreamService dreams;

    public DreamCommand(DreamService dreams) { this.dreams = dreams; }
    public void quest(Player player, String action) { dreams.quest(player, action); }
    public void invite(Player player, String name) { dreams.invite(player, name); }
    public void join(Player player, String name) { dreams.join(player, name); }
    public void leave(Player player) { dreams.leaveShared(player); }
    public void weekly(Player player) { dreams.weekly(player); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Only players can use this command."); return true; }
        return run(player, command.getName());
    }

    /** Runs {@code /dream}, {@code /wake} or {@code /dreamloot} (English name) for {@code player}. */
    public boolean run(Player player, String name) {
        switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "dream" -> dreams.enter(player);
            case "wake" -> dreams.wake(player, Vystorm.vystorm_core.worlds.ExitReason.COMMAND);
            case "dreamloot" -> dreams.claim(player);
            default -> { return false; }
        }
        return true;
    }

    /** /dream, /wake and /dreamloot take no arguments; suppress Bukkit's default player-name suggestions. */
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
