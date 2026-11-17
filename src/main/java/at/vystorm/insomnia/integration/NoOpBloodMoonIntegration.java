package at.vystorm.insomnia.integration;

import org.bukkit.World;
import org.bukkit.entity.Player;

public final class NoOpBloodMoonIntegration implements BloodMoonIntegration {
    @Override public boolean isBloodMoon(World world) { return false; }
    @Override public double sleepQualityMultiplier(Player player) { return 1.0; }
    @Override public double insomniaSpawnMultiplier(Player player) { return 1.0; }
    @Override public boolean available() { return false; }
}
