package at.vystorm.insomnia.integration;

import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.core.CoreBridge;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;


public interface CityClaimIntegration {
    double sleepQualityMultiplier(Player player);
    boolean available();

    /** Spawn, safe, arena and market zones (Core priority market or higher): no Insomnia mobs there. */
    default boolean protectedArea(Location location) { return false; }

    static CityClaimIntegration detect(JavaPlugin plugin, CoreBridge core, InsomniaConfig config) {
        // Stadtgebiete und Mietflächen kommen aus dem gemeinsamen Core-Regionsregister (von CityClaim veröffentlicht).
        // Der Schutzbereich-Check gilt unabhängig vom Stadt-Bonus.
        core.mid("Core region registry enabled; city bonus=" + config.cityEnabled());
        return new Regions(config, core);
    }

    /** Oberste Region an der Position: Mietfläche vor Stadt; eine Arena o. ä. darüber hebt den Bonus auf. */
    final class Regions implements CityClaimIntegration {
        static final String KIND_CITY = "city";
        static final String KIND_RENTAL = "city-plot";
        private final InsomniaConfig config;
        private final CoreBridge core;
        private Regions(InsomniaConfig config, CoreBridge core) { this.config = config; this.core = core; }

        @Override
        public boolean protectedArea(Location location) {
            try {
                var top = core.api().area().regions().top(location);
                return top.isPresent() && top.get().priority() >= Vystorm.vystorm_core.region.RegionPriority.MARKET;
            } catch (RuntimeException failure) {
                return false; // registry trouble must not stop the mob tick; a missed check only allows a spawn
            }
        }

        @Override
        public double sleepQualityMultiplier(Player player) {
            if (!config.cityEnabled()) return 1.0;
            String kind;
            try {
                var top = core.api().area().regions().top(player.getLocation());
                if (top.isEmpty()) return 1.0;
                kind = top.get().kind();
            } catch (RuntimeException failure) {
                return 1.0; // registry trouble costs the bonus, never the sleep
            }
            // Core has no public constants for these kinds; they are the ones CityClaim registers.
            return switch (kind) {
                case KIND_RENTAL -> config.sleepQuality("rental_multiplier", 1.20);
                case KIND_CITY -> config.sleepQuality("city_multiplier", 1.15);
                default -> 1.0;
            };
        }

        @Override public boolean available() { return config.cityEnabled(); }
    }
}
