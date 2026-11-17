package at.vystorm.insomnia.integration;

import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.core.CoreBridge;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;

public interface BloodMoonIntegration {
    boolean isBloodMoon(World world);
    double sleepQualityMultiplier(Player player);
    double insomniaSpawnMultiplier(Player player);
    boolean available();

    static BloodMoonIntegration detect(JavaPlugin plugin, CoreBridge core, InsomniaConfig config) {
        try {
            Class<?> apiClass = Class.forName("at.vystorm.bloodmoon.api.BloodMoonApi");
            @SuppressWarnings({"rawtypes", "unchecked"})
            RegisteredServiceProvider<?> registration = plugin.getServer().getServicesManager().getRegistration((Class) apiClass);
            if (registration == null || registration.getProvider() == null) return new NoOpBloodMoonIntegration();
            Object provider = registration.getProvider();
            Method active = apiClass.getMethod("isBloodMoon", World.class);
            Method sleep = apiClass.getMethod("sleepQualityMultiplier", Player.class);
            Method spawn = apiClass.getMethod("insomniaSpawnMultiplier", Player.class);
            if (config.bloodMoonEnabled()) core.mid("BloodMoon integration enabled through public service API");
            return new Reflective(provider, active, sleep, spawn, config);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return new NoOpBloodMoonIntegration();
        }
    }

    final class Reflective implements BloodMoonIntegration {
        private final Object provider;
        private final Method active;
        private final Method sleep;
        private final Method spawn;
        private final InsomniaConfig config;

        private Reflective(Object provider, Method active, Method sleep, Method spawn, InsomniaConfig config) {
            this.provider = provider;
            this.active = active;
            this.sleep = sleep;
            this.spawn = spawn;
            this.config = config;
        }

        @Override public boolean isBloodMoon(World world) {
            if (!config.bloodMoonEnabled()) return false;
            try { return Boolean.TRUE.equals(active.invoke(provider, world)); }
            catch (ReflectiveOperationException ignored) { return false; }
        }

        @Override public double sleepQualityMultiplier(Player player) {
            if (!config.bloodMoonEnabled()) return 1.0;
            try { return ((Number) sleep.invoke(provider, player)).doubleValue(); }
            catch (ReflectiveOperationException | ClassCastException ignored) { return 1.0; }
        }

        @Override public double insomniaSpawnMultiplier(Player player) {
            if (!config.bloodMoonEnabled()) return 1.0;
            try { return ((Number) spawn.invoke(provider, player)).doubleValue(); }
            catch (ReflectiveOperationException | ClassCastException ignored) { return 1.0; }
        }

        @Override public boolean available() { return config.bloodMoonEnabled(); }
    }
}
