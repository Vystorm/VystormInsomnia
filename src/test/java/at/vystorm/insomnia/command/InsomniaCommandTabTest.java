package at.vystorm.insomnia.command;

import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.service.FatigueService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InsomniaCommandTabTest {
    /** Every first-level and admin subcommand InsomniaCommand#onCommand handles. */
    private static final List<String> HANDLED = List.of("menu", "status", "history", "triggers", "admin");
    private static final List<String> ADMIN_HANDLED = List.of("get", "setfatigue", "setdebt", "sleep", "effect", "spawnmob", "reload", "overview");

    private static CommandSender sender(Set<String> nodes) {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenAnswer(call -> nodes.contains(call.<String>getArgument(0)));
        return sender;
    }

    private static InsomniaCommand command(InsomniaConfig config, FatigueService fatigue) {
        return new InsomniaCommand(config, fatigue, null, null, null, null, () -> {}, null);
    }

    @Test
    void everyHandledSubcommandIsSuggestedOnlyWithItsPermission() {
        InsomniaCommand command = command(null, null);
        CommandSender all = sender(Set.of(InsomniaCommand.USE, InsomniaCommand.ADMIN));
        assertEquals(HANDLED, command.onTabComplete(all, mock(Command.class), "insomnia", new String[]{""}));
        assertEquals(ADMIN_HANDLED, command.onTabComplete(all, mock(Command.class), "insomnia", new String[]{"ADMIN", ""}));
        CommandSender player = sender(Set.of(InsomniaCommand.USE));
        assertEquals(InsomniaCommand.PLAYER_SUBCOMMANDS, command.onTabComplete(player, mock(Command.class), "insomnia", new String[]{""}));
        assertTrue(command.onTabComplete(player, mock(Command.class), "insomnia", new String[]{"admin", ""}).isEmpty());
        assertTrue(command.onTabComplete(sender(Set.of()), mock(Command.class), "insomnia", new String[]{""}).isEmpty());
        assertEquals(List.of("setfatigue", "setdebt"), command.onTabComplete(all, mock(Command.class), "insomnia", new String[]{"admin", "Set"}));
    }

    @Test
    void playerSubcommandsCheckUseOnExecution() {
        InsomniaConfig config = at.vystorm.insomnia.SwitchDefaults.on(mock(InsomniaConfig.class));
        when(config.message(any(), anyString())).thenReturn("no");
        FatigueService fatigue = mock(FatigueService.class);
        CommandSender nobody = sender(Set.of());
        command(config, fatigue).onCommand(nobody, mock(Command.class), "insomnia", new String[]{"history"});
        verify(config).message(nobody, "no_permission");
        verifyNoInteractions(fatigue);
    }

    @Test
    void incompleteOrUnknownAdminCommandsShowUsageInsteadOfPlayerNotFound() {
        InsomniaConfig config = at.vystorm.insomnia.SwitchDefaults.on(mock(InsomniaConfig.class));
        when(config.message(any(), anyString())).thenReturn("msg");
        FatigueService fatigue = mock(FatigueService.class);
        CommandSender admin = sender(Set.of(InsomniaCommand.USE, InsomniaCommand.ADMIN));
        InsomniaCommand command = command(config, fatigue);

        command.onCommand(admin, mock(Command.class), "insomnia", new String[]{"admin", "get"});
        verify(admin).sendMessage("/insomnia admin get <player>");
        command.onCommand(admin, mock(Command.class), "insomnia", new String[]{"admin", "setfatigue", "Steve"});
        verify(admin).sendMessage("/insomnia admin setfatigue <player> <0-100|+n|-n>");
        command.onCommand(admin, mock(Command.class), "insomnia", new String[]{"admin", "spawnmob", "Steve"});
        verify(admin).sendMessage("/insomnia admin spawnmob <player> <tier>");
        command.onCommand(admin, mock(Command.class), "insomnia", new String[]{"admin", "bogus", "Steve"});
        // The general admin usage now comes from the language files.
        verify(config).message(admin, "admin_usage");
        verify(admin).sendMessage("msg");

        verify(config, never()).message(any(), eq("player_not_found"));
        verifyNoInteractions(fatigue);
    }

    @Test
    void overviewOpensTheAdminOverviewAndNeedsNoPlayerArgument() {
        InsomniaConfig config = at.vystorm.insomnia.SwitchDefaults.on(mock(InsomniaConfig.class));
        when(config.message(any(), anyString())).thenReturn("msg");
        CommandSender admin = sender(Set.of(InsomniaCommand.USE, InsomniaCommand.ADMIN));
        InsomniaCommand command = command(config, mock(FatigueService.class));

        // Without the overview (not wired) the general usage is shown instead of an error.
        command.onCommand(admin, mock(Command.class), "insomnia", new String[]{"admin", "overview"});
        verify(config).message(admin, "admin_usage");

        at.vystorm.insomnia.ui.FatigueOverview overview = mock(at.vystorm.insomnia.ui.FatigueOverview.class);
        command.overview(overview);
        command.onCommand(admin, mock(Command.class), "insomnia", new String[]{"admin", "OVERVIEW"});
        verify(overview).open(admin);

        assertEquals(List.of(), command.onTabComplete(admin, mock(Command.class), "insomnia", new String[]{"admin", "overview", ""}));
        assertEquals("/insomnia admin overview", InsomniaCommand.adminUsage("overview"));
    }

    @Test
    void adminValuesRejectNonFiniteNumbersAndClampFiniteOnes() {
        assertNull(InsomniaCommand.number(new String[]{"NaN"}, 0, 50.0));
        assertNull(InsomniaCommand.number(new String[]{"Infinity"}, 0, 50.0));
        assertNull(InsomniaCommand.number(new String[]{"-Infinity"}, 0, 50.0));
        assertNull(InsomniaCommand.number(new String[]{"abc"}, 0, 50.0));
        assertNull(InsomniaCommand.number(new String[]{}, 0, 50.0));
        assertEquals(100.0, InsomniaCommand.number(new String[]{"250"}, 0, 50.0));
        assertEquals(42.5, InsomniaCommand.number(new String[]{"42.5"}, 0, 50.0));
    }

    @Test
    void signedAdminValuesChangeTheCurrentValueAndStayInRange() {
        assertEquals(60.0, InsomniaCommand.number(new String[]{"+10"}, 0, 50.0));
        assertEquals(45.0, InsomniaCommand.number(new String[]{"-5"}, 0, 50.0));
        assertEquals(100.0, InsomniaCommand.number(new String[]{"+30"}, 0, 90.0));
        assertEquals(0.0, InsomniaCommand.number(new String[]{"-30"}, 0, 10.0));
        assertNull(InsomniaCommand.number(new String[]{"+NaN"}, 0, 50.0));
        assertNull(InsomniaCommand.number(new String[]{"+"}, 0, 50.0));
        // Without a sign the value is still absolute.
        assertEquals(10.0, InsomniaCommand.number(new String[]{"10"}, 0, 90.0));
    }

    @Test
    void minutesToNextStageUseTheBaseRateAndRoundUp() {
        List<Double> stages = List.of(20.0, 40.0, 60.0, 80.0);
        assertEquals(160, InsomniaCommand.minutesToNextStage(0.0, stages, 0.125));
        assertEquals(80, InsomniaCommand.minutesToNextStage(30.0, stages, 0.125));
        assertEquals(1, InsomniaCommand.minutesToNextStage(39.99, stages, 0.125));
        assertEquals(160, InsomniaCommand.minutesToNextStage(40.0, stages, 0.125));
        assertEquals(-1, InsomniaCommand.minutesToNextStage(80.0, stages, 0.125));
        assertEquals(-1, InsomniaCommand.minutesToNextStage(10.0, stages, 0.0));
    }

    @Test
    void dreamCommandsSuggestNothing() {
        DreamCommand dream = new DreamCommand(null);
        assertEquals(List.of(), dream.onTabComplete(sender(Set.of()), mock(Command.class), "dream", new String[]{""}));
        assertEquals(List.of(), dream.onTabComplete(sender(Set.of()), mock(Command.class), "wake", new String[]{"St"}));
    }

    @Test
    void setFatigueUnderAStimulantMovesTheShownValueByExactlyTheGivenAmount() {
        at.vystorm.insomnia.model.PlayerFatigueState state = new at.vystorm.insomnia.model.PlayerFatigueState(java.util.UUID.randomUUID(), 0L);
        state.fatigue(70.0);
        state.stimulantSuppression(20.0); // shown 50
        state.fatigue(InsomniaCommand.rawForShown(state, InsomniaCommand.number(new String[]{"+10"}, 0, state.fatigue())));
        assertEquals(60.0, state.fatigue(), 1e-9);
        state.fatigue(InsomniaCommand.rawForShown(state, InsomniaCommand.number(new String[]{"-30"}, 0, state.fatigue())));
        assertEquals(30.0, state.fatigue(), 1e-9);
        state.fatigue(InsomniaCommand.rawForShown(state, InsomniaCommand.number(new String[]{"40"}, 0, state.fatigue())));
        assertEquals(40.0, state.fatigue(), 1e-9);
        state.fatigue(InsomniaCommand.rawForShown(state, InsomniaCommand.number(new String[]{"-100"}, 0, state.fatigue())));
        assertEquals(0.0, state.fatigue(), 1e-9);
    }
}
