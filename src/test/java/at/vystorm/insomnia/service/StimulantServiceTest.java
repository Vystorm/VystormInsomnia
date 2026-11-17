package at.vystorm.insomnia.service;

import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.core.CoreBridge;
import at.vystorm.insomnia.model.PlayerFatigueState;
import at.vystorm.insomnia.testutil.TestServer;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StimulantServiceTest {
    private Server server;
    private InsomniaConfig config;
    private CoreBridge core;
    private FatigueService fatigue;
    private StimulantService service;
    private Player player;
    private PlayerFatigueState state;

    @BeforeEach
    void setUp() {
        server = TestServer.fresh();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("stimulants.coffee.material", "HONEY_BOTTLE");
        yaml.set("stimulants.coffee.fatigue_reduction", 20.0);
        yaml.set("stimulants.coffee.duration_seconds", 60);
        yaml.set("stimulants.coffee.tolerance_step", 0.25);
        yaml.set("stimulants.coffee.crash_fatigue", 10.0);
        yaml.set("stimulants.coffee.max_effective_uses_before_sleep", 3);
        yaml.set("stimulants.energy.material", "POTION");
        yaml.set("stimulants.energy.nbt_key", "insomnia_stimulant");
        yaml.set("stimulants.energy.nbt_value", "energy_drink");
        yaml.set("stimulants.energy.fatigue_reduction", 30.0);
        yaml.set("stimulants.energy.duration_seconds", 60);
        yaml.set("stimulants.broken.material", "NOT_AN_ITEM");
        config = mock(InsomniaConfig.class);
        when(config.stimulants()).thenReturn(yaml.getConfigurationSection("stimulants"));
        when(config.stimulantsEnabled()).thenReturn(true);
        when(config.message(any(), eq("stimulant_crash"))).thenReturn("crash");
        core = mock(CoreBridge.class);
        fatigue = mock(FatigueService.class);
        when(fatigue.ready(any(UUID.class))).thenReturn(true);
        service = new StimulantService(config, core, fatigue);

        UUID id = UUID.randomUUID();
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        state = new PlayerFatigueState(id, 0L);
        state.fatigue(70.0);
        when(fatigue.require(id)).thenReturn(state);
        when(fatigue.states()).thenReturn(List.of(state));
        when(server.getPlayer(id)).thenReturn(player);
    }

    private static ItemStack item(Material material) {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(material);
        return item;
    }

    @Test
    void toleranceWeakensEachUseAndStopsAfterTheLimit() {
        ItemStack coffee = item(Material.HONEY_BOTTLE);
        assertTrue(service.consume(player, coffee, 0L));
        assertEquals(20.0, state.stimulantSuppression(), 1e-9);
        assertEquals(50.0, state.fatigue(), 1e-9);
        assertEquals(10.0, state.stimulantCrashPending(), 1e-9);

        service.consume(player, coffee, 0L);                        // 75 %
        assertEquals(35.0, state.stimulantSuppression(), 1e-9);
        service.consume(player, coffee, 0L);                        // 50 %
        assertEquals(45.0, state.stimulantSuppression(), 1e-9);
        service.consume(player, coffee, 0L);                        // limit of 3 reached: no effect
        assertEquals(45.0, state.stimulantSuppression(), 1e-9);
        assertEquals(4, state.stimulantUses());
        // crash still grows, but at least a quarter of the full crash per use
        assertEquals(10.0 + 7.5 + 5.0 + 2.5, state.stimulantCrashPending(), 1e-9);
        assertEquals(60_000L, state.stimulantUntilEpochMillis());
    }

    @Test
    void nbtBoundStimulantsNeedTheMatchingTag() {
        ItemStack plainPotion = item(Material.POTION);
        ItemStack energy = item(Material.POTION);
        when(core.getNbt(energy, "insomnia_stimulant")).thenReturn("ENERGY_DRINK");

        assertFalse(service.consume(player, plainPotion, 0L));
        assertEquals(0, state.stimulantUses());
        assertTrue(service.consume(player, energy, 0L));
        assertEquals(30.0, state.stimulantSuppression(), 1e-9);
    }

    @Test
    void disabledStimulantsAndUnknownItemsDoNothing() {
        assertFalse(service.consume(player, item(Material.BREAD), 0L));
        when(config.stimulantsEnabled()).thenReturn(false);
        assertFalse(service.consume(player, item(Material.HONEY_BOTTLE), 0L));
        assertEquals(0, state.stimulantUses());
        assertTrue(String.join("\n", service.warnings()).contains("NOT_AN_ITEM"));
    }

    @Test
    void forecastMatchesTheActualCrashAfterRepeatedDoses() {
        service.consume(player, item(Material.HONEY_BOTTLE), 0L);
        service.consume(player, item(Material.HONEY_BOTTLE), 10_000L);
        StimulantForecast forecast = StimulantForecast.of(state, 20_000L);
        assertEquals(50, forecast.seconds());
        assertEquals(2, forecast.uses());
        service.tick(70_000L);
        assertEquals(forecast.fatigueAfterCrash(), state.fatigue());
        assertNull(StimulantForecast.of(state, 70_000L));
    }

    @Test
    void crashHitsOnlyAfterTheEffectEnds() {
        service.consume(player, item(Material.HONEY_BOTTLE), 0L);
        service.tick(59_999L);
        assertEquals(20.0, state.stimulantSuppression(), 1e-9);
        verify(player, never()).sendMessage("crash");

        service.tick(60_000L);
        assertEquals(0.0, state.stimulantSuppression(), 1e-9);
        assertEquals(0.0, state.stimulantCrashPending(), 1e-9);
        assertEquals(0L, state.stimulantUntilEpochMillis());
        assertEquals(80.0, state.fatigue(), 1e-9);
        verify(fatigue, atLeastOnce()).refreshStage(player.getUniqueId());
        verify(player).sendMessage("crash");

        service.tick(120_000L); // nothing pending any more
        assertEquals(80.0, state.fatigue(), 1e-9);
    }
}
