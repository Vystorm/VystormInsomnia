package at.vystorm.bloodmoon.api;

import org.bukkit.World;
import org.bukkit.entity.Player;

/** Test-only service contract used by Insomnia's reflective optional adapter. */
public interface BloodMoonApi {
    boolean isBloodMoon(World world);
    double sleepQualityMultiplier(Player player);
    double insomniaSpawnMultiplier(Player player);
}
