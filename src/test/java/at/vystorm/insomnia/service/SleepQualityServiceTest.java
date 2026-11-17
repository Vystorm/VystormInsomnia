package at.vystorm.insomnia.service;

import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.integration.BloodMoonIntegration;
import at.vystorm.insomnia.integration.CityClaimIntegration;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SleepQualityServiceTest {
    @Test
    void qualityIsClampedAtConfiguredMaximumAfterAllPositiveMultipliers() {
        InsomniaConfig config = qualityConfig(Map.of("base", 10.0, "max", 2.0, "min", 0.25));
        Player player = playerAt(70, false, 0);
        SleepQualityService service = new SleepQualityService(config, cityMultiplier(1.5), bloodMoonMultiplier(1.5));

        assertEquals(2.0, service.calculate(player));
    }

    @Test
    void qualityIsClampedAtConfiguredMinimumAfterNegativeConditions() {
        InsomniaConfig config = qualityConfig(Map.of("base", 0.01, "max", 2.0, "min", 0.25));
        Player player = playerAt(63, true, 15);
        SleepQualityService service = new SleepQualityService(config, cityMultiplier(0.1), bloodMoonMultiplier(0.1));

        assertEquals(0.25, service.calculate(player));
    }

    private static InsomniaConfig qualityConfig(Map<String, Double> values) {
        InsomniaConfig config = mock(InsomniaConfig.class);
        when(config.sleepQuality(anyString(), anyDouble())).thenAnswer(invocation ->
                values.getOrDefault(invocation.getArgument(0, String.class), invocation.getArgument(1, Double.class)));
        return config;
    }

    private static Player playerAt(int highestY, boolean storm, int light) {
        World world = mock(World.class);
        Location location = mock(Location.class);
        Block block = mock(Block.class);
        when(world.getHighestBlockYAt(location)).thenReturn(highestY);
        when(location.getWorld()).thenReturn(world);
        when(location.getBlockY()).thenReturn(64);
        when(location.getBlock()).thenReturn(block);
        when(block.getLightLevel()).thenReturn((byte) light);
        when(world.getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble(), any())).thenReturn(List.of());
        when(world.hasStorm()).thenReturn(storm);
        Player player = mock(Player.class);
        when(player.getLocation()).thenReturn(location);
        return player;
    }

    private static CityClaimIntegration cityMultiplier(double value) {
        CityClaimIntegration city = mock(CityClaimIntegration.class);
        when(city.sleepQualityMultiplier(any(Player.class))).thenReturn(value);
        return city;
    }

    private static BloodMoonIntegration bloodMoonMultiplier(double value) {
        BloodMoonIntegration bloodMoon = mock(BloodMoonIntegration.class);
        when(bloodMoon.sleepQualityMultiplier(any(Player.class))).thenReturn(value);
        return bloodMoon;
    }
}
