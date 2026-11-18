package at.vystorm.insomnia.service;

import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.integration.BloodMoonIntegration;
import at.vystorm.insomnia.integration.CityClaimIntegration;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;

public final class SleepQualityService {
    private final InsomniaConfig config;
    private final CityClaimIntegration city;
    private final BloodMoonIntegration bloodMoon;

    public SleepQualityService(InsomniaConfig config, CityClaimIntegration city, BloodMoonIntegration bloodMoon) {
        this.config = config;
        this.city = city;
        this.bloodMoon = bloodMoon;
    }

    public double calculate(Player player) {
        Location location = player.getLocation();
        World world = location.getWorld();
        double quality = config.sleepQuality("base", 1.0);
        double min = config.sleepQuality("min", 0.25);
        double max = config.sleepQuality("max", 2.0);
        // sleep.quality.enabled false (0.31.0): every sleep has the base quality; nothing around the bed is checked.
        if (!config.sleepQualityEnabled()) return Math.max(min, Math.min(max, quality));

        int highestY = world.getHighestBlockYAt(location);
        boolean roof = highestY > location.getBlockY() + 1;
        quality *= roof ? config.sleepQuality("roof_multiplier", 1.10)
                : config.sleepQuality("exposed_multiplier", 0.85);

        int light = location.getBlock().getLightLevel();
        if (light <= 7) quality *= config.sleepQuality("low_light_multiplier", 1.05);
        else if (light >= 12) quality *= config.sleepQuality("bright_light_multiplier", 0.90);

        boolean monsterNearby = !world.getNearbyEntities(location, 8.0, 4.0, 8.0,
                entity -> entity instanceof Monster).isEmpty();
        if (monsterNearby) quality *= config.sleepQuality("monster_nearby_multiplier", 0.70);

        if (world.hasStorm() && !roof) quality *= config.sleepQuality("storm_exposed_multiplier", 0.75);
        quality *= city.sleepQualityMultiplier(player);
        quality *= bloodMoon.sleepQualityMultiplier(player);
        if (config.cozyEnabled()) quality *= comfort(location).multiplier(config.cozyPerFurnishing());
        return Math.max(min, Math.min(max, quality));
    }

    /** Furnishings around a sleeping spot (see {@link BedroomComfort}). */
    public BedroomComfort.Result comfort(Location location) {
        return BedroomComfort.scan(location, (int) Math.round(config.sleepQuality("cozy_radius", 4)));
    }
}
