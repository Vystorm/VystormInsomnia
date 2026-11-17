package at.vystorm.insomnia.dream;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

/** A visit's remaining-time display. Construct, update and close on the server thread. */
public final class DreamTimeBar {
    private final BossBar bar;
    private final long durationMillis;

    public DreamTimeBar(Player player, long durationMillis) {
        this.durationMillis = Math.max(1L, durationMillis);
        bar = Bukkit.createBossBar("", BarColor.PURPLE, BarStyle.SOLID);
        bar.addPlayer(player);
    }

    public void update(long remainingMillis, String title) {
        bar.setTitle(title);
        bar.setProgress(progress(remainingMillis, durationMillis));
        bar.setColor(remainingMillis <= 30_000L ? BarColor.RED
                : remainingMillis <= 60_000L ? BarColor.YELLOW : BarColor.PURPLE);
    }

    public void close() {
        bar.removeAll();
    }

    static double progress(long remainingMillis, long durationMillis) {
        return Math.max(0.0, Math.min(1.0, (double) remainingMillis / Math.max(1L, durationMillis)));
    }

    static String timeText(long remainingMillis) {
        long millis = Math.max(0L, remainingMillis);
        // Avoid overflowing by adding the ceiling adjustment after division.
        long seconds = millis / 1_000L + (millis % 1_000L == 0 ? 0 : 1);
        long remainder = seconds % 60L;
        return seconds / 60L + ":" + (remainder < 10 ? "0" : "") + remainder;
    }
}
