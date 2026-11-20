package at.vystorm.insomnia.dream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import at.vystorm.insomnia.SwitchDefaults;
import at.vystorm.insomnia.command.InsomniaCommand;
import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.service.FatigueService;
import java.util.HashMap;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 0.31.1: a dream command whose own switch is on but whose base switch is off names that base switch. */
class DreamNeedsSwitchTest {
    private DreamService service;
    private InsomniaConfig config;
    private Player player;
    private FatigueService fatigue;

    @BeforeEach
    void setUp() throws Exception {
        service = mock(DreamService.class, CALLS_REAL_METHODS);
        config = SwitchDefaults.on(mock(InsomniaConfig.class));
        when(config.fatigueEnabled()).thenReturn(true);
        when(config.dreamEnabled()).thenReturn(true);
        when(config.dreamQuestsEnabled()).thenReturn(true);
        when(config.dreamWeeklyEnabled()).thenReturn(true);
        when(config.message(any(), anyString(), any(Object[].class))).thenAnswer(i -> i.getArgument(1));
        when(config.needs(any(), anyString())).thenAnswer(i -> "needs " + i.getArgument(1));
        fatigue = mock(FatigueService.class);
        Plugin plugin = mock(Plugin.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(fatigue.ready(id)).thenReturn(true);
        field("config", config);
        field("fatigue", fatigue);
        field("plugin", plugin);
        field("active", new HashMap<>());
        field("naps", new HashMap<>());
        field("enabled", true);
    }

    private void field(String name, Object value) throws Exception {
        var field = DreamService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }

    @Test void givenDreamOff_whenQuestWeeklyInviteOrJoin_thenTheAnswerNamesTheDreamSwitch() {
        when(config.dreamEnabled()).thenReturn(false);
        service.quest(player, "status");
        service.weekly(player);
        service.invite(player, "Alex");
        service.join(player, "Alex");
        verify(player, times(4)).sendMessage("needs dream.enabled");
        verify(player, never()).sendMessage(InsomniaCommand.OFF);
    }

    @Test void givenTheirOwnSwitchOff_whenQuestOrWeekly_thenThePlainOffMessageStays() {
        when(config.dreamEnabled()).thenReturn(false);
        when(config.dreamQuestsEnabled()).thenReturn(false);
        when(config.dreamWeeklyEnabled()).thenReturn(false);
        service.quest(player, "status");
        service.weekly(player);
        verify(player, times(2)).sendMessage(InsomniaCommand.OFF);
    }

    @Test void givenFatigueOff_whenTooAwakeToDream_thenTheAnswerNamesTheFatigueSwitch() {
        when(config.fatigueEnabled()).thenReturn(false);
        when(config.dreamMinimumFatigue()).thenReturn(40.0);
        when(fatigue.fatigue(player.getUniqueId())).thenReturn(10.0);
        service.enter(player);
        verify(player).sendMessage("needs fatigue.enabled");
    }
}
