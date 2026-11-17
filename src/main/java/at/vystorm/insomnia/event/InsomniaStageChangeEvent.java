package at.vystorm.insomnia.event;

import at.vystorm.insomnia.api.InsomniaStage;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public final class InsomniaStageChangeEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final InsomniaStage previous;
    private final InsomniaStage current;

    public InsomniaStageChangeEvent(Player player, InsomniaStage previous, InsomniaStage current) {
        this.player = player;
        this.previous = previous;
        this.current = current;
    }

    public Player player() { return player; }
    public InsomniaStage previous() { return previous; }
    public InsomniaStage current() { return current; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}
