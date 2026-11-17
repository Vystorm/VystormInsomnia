package at.vystorm.insomnia.event;

import at.vystorm.insomnia.api.SleepStage;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public final class PlayerSleepStageChangeEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final SleepStage previous;
    private final SleepStage current;

    public PlayerSleepStageChangeEvent(Player player, SleepStage previous, SleepStage current) {
        this.player = player;
        this.previous = previous;
        this.current = current;
    }

    public Player player() { return player; }
    public SleepStage previous() { return previous; }
    public SleepStage current() { return current; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}
