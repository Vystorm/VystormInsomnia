package at.vystorm.insomnia.command;

import Vystorm.vystorm_core.lang.Lang;
import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.model.PlayerFatigueState;
import at.vystorm.insomnia.service.FatigueService;
import at.vystorm.insomnia.service.RestedBonusService;
import at.vystorm.insomnia.testutil.FakePdc;
import at.vystorm.insomnia.testutil.TestServer;
import at.vystorm.insomnia.ui.Messages;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StimulantStatusTest {
    @Test
    void statusAndAdminGetRenderLocalizedForecastAndHideItAfterFullRest() throws Exception {
        var server = TestServer.fresh();
        Map<String, String> files = new LinkedHashMap<>();
        for (String code : new String[]{"en", "de", "zh_cn"}) {
            try (var in = getClass().getResourceAsStream("/lang/" + code + ".yml")) {
                files.put(code, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        Messages messages = new Messages(Lang.create("VystormInsomnia", files));
        InsomniaConfig config = mock(InsomniaConfig.class);
        when(config.messages()).thenReturn(messages);
        when(config.message(any(), anyString(), any(Object[].class))).thenReturn("status");
        FatigueService fatigue = mock(FatigueService.class);
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn("Alex");
        when(player.hasPermission(InsomniaCommand.USE)).thenReturn(true);
        FakePdc pdc = new FakePdc();
        when(player.getPersistentDataContainer()).thenReturn(pdc.container);
        when(server.getPlayerExact("Alex")).thenReturn(player);
        PlayerFatigueState state = new PlayerFatigueState(id, 0);
        state.fatigue(70);
        state.stimulantSuppression(20);
        state.stimulantCrashPending(10);
        state.stimulantUses(1);
        state.stimulantUntilEpochMillis(System.currentTimeMillis() + 60_000);
        when(fatigue.ready(id)).thenReturn(true);
        when(fatigue.require(id)).thenReturn(state);
        InsomniaCommand command = new InsomniaCommand(config, fatigue, null, null, null,
                mock(RestedBonusService.class), () -> {}, null);
        for (Locale locale : new Locale[]{Locale.US, Locale.GERMANY, Locale.SIMPLIFIED_CHINESE}) {
            when(player.locale()).thenReturn(locale);
            command.execute(player, "insomnia", "status");
            verify(player).sendMessage(argThat((String text) -> text != null && text.contains("80.0%")
                    && !text.contains("{") && !text.equals("status_stimulant")));
            clearInvocations(player);
        }
        CommandSender admin = mock(CommandSender.class);
        when(admin.hasPermission(InsomniaCommand.ADMIN)).thenReturn(true);
        command.execute(admin, "insomnia", "admin", "get", "Alex");
        verify(admin).sendMessage(contains("80.0%"));
        state.resetStimulants();
        command.execute(player, "insomnia", "status");
        verify(player, never()).sendMessage(contains("80.0%"));
    }
}
