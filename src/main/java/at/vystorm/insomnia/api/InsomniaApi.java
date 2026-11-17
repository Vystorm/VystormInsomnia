package at.vystorm.insomnia.api;

import java.util.UUID;

/**
 * Public Insomnia service (Bukkit ServicesManager). Values exist only for online players: the read methods return
 * neutral values (0, {@link InsomniaStage#RESTED}, false) for offline players, the write methods throw.
 */
public interface InsomniaApi {
    double fatigue(UUID player);
    double sleepDebt(UUID player);
    InsomniaStage stage(UUID player);
    boolean isSleeping(UUID player);

    /** @throws IllegalStateException if the player is not online */
    void addFatigue(UUID player, double amount);

    /** @throws IllegalStateException if the player is not online */
    void reduceFatigue(UUID player, double amount);
}
