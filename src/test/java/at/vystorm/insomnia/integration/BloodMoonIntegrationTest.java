package at.vystorm.insomnia.integration;

import at.vystorm.bloodmoon.api.BloodMoonApi;
import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.core.CoreBridge;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BloodMoonIntegrationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void integrationToggleFollowsReloadedConfiguration(boolean initial) {
        InsomniaConfig config = mock(InsomniaConfig.class);
        when(config.bloodMoonEnabled()).thenReturn(initial);
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        ServicesManager services = mock(ServicesManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getServicesManager()).thenReturn(services);
        BloodMoonApi provider = mock(BloodMoonApi.class);
        when(services.getRegistration(BloodMoonApi.class)).thenReturn(
                new RegisteredServiceProvider<>(BloodMoonApi.class, provider, ServicePriority.Normal, plugin));
        Player player = mock(Player.class);
        World world = mock(World.class);
        when(provider.isBloodMoon(world)).thenReturn(true);
        when(provider.sleepQualityMultiplier(player)).thenReturn(0.5);
        when(provider.insomniaSpawnMultiplier(player)).thenReturn(2.0);
        BloodMoonIntegration integration = BloodMoonIntegration.detect(plugin, mock(CoreBridge.class), config);
        assertEquals(initial, integration.available());
        when(config.bloodMoonEnabled()).thenReturn(!initial);
        assertEquals(!initial, integration.available());
        assertEquals(!initial, integration.isBloodMoon(world));
        assertEquals(initial ? 1.0 : 0.5, integration.sleepQualityMultiplier(player));
        assertEquals(initial ? 1.0 : 2.0, integration.insomniaSpawnMultiplier(player));
    }
}
