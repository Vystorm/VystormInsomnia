package at.vystorm.insomnia.ui;

import Vystorm.vystorm_core.panel.PanelContext;
import at.vystorm.insomnia.api.InsomniaStage;
import at.vystorm.insomnia.model.PlayerFatigueState;
import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelTesting;
import Vystorm.vystorm_core.web.ClientUiTesting;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientModTest {
    @Test
    void fatigueBarCarriesValueStageColourAndLabel() {
        JsonObject bar = FatigueHud.fatigueBar(42, InsomniaStage.EXHAUSTED, "Fatigue 42 % · Exhausted").toJson();
        assertEquals("bar", bar.get("type").getAsString());
        assertEquals("fatigue", bar.get("id").getAsString());
        assertEquals(42, bar.get("value").getAsDouble());
        assertEquals(100, bar.get("max").getAsDouble());
        assertEquals(FatigueHud.color(InsomniaStage.EXHAUSTED), bar.get("color").getAsString());
        assertTrue(bar.toString().contains("Fatigue 42 %"));
        assertEquals("debt", FatigueHud.debtBar(10, "Sleep debt 10 %").toJson().get("id").getAsString());
    }

    @Test
    void stageToastStyleFollowsSeverity() {
        assertEquals("error", FatigueHud.stageToast("Overtired", "x", InsomniaStage.OVERMUEDET).toJson().get("style").getAsString());
        assertEquals("info", FatigueHud.stageToast("Tired", "x", InsomniaStage.TIRED).toJson().get("style").getAsString());
        for (InsomniaStage stage : InsomniaStage.values()) assertTrue(FatigueHud.color(stage).matches("#[0-9A-F]{8}"));
    }

    @Test
    void panelViewReadsTheState() {
        PlayerFatigueState state = new PlayerFatigueState(UUID.randomUUID(), 0L);
        state.fatigue(55);
        state.sleepDebt(12.5);
        state.stimulantUntilEpochMillis(130_000L);
        FatiguePanel.View view = FatiguePanel.View.of(state, 7, true, 10_000L);
        assertEquals(55, view.fatigue());
        assertEquals(12.5, view.debt());
        assertEquals(2, view.stimulantMinutes());
        assertEquals(7, view.restedMinutes());
        assertTrue(view.sleeping());
        assertEquals(0, FatiguePanel.View.of(state, 0, false, 200_000L).stimulantMinutes());
    }

    @Test
    void panelBodyListsStimulantAndSleepOnlyWhenActive() {
        PanelContext ctx = mock(PanelContext.class);
        when(ctx.text(anyString(), any(Object[].class))).thenAnswer(i -> i.getArgument(0));
        when(ctx.text(anyString())).thenAnswer(i -> i.getArgument(0));
        var quiet = new FatiguePanel.View(InsomniaStage.TIRED, 30, 5, 0, 0, false);
        var busy = new FatiguePanel.View(InsomniaStage.TIRED, 30, 5, 0, 3, true);
        assertEquals(4, FatiguePanel.elements(quiet, ctx, "Tired", "hint", "no").size());
        assertEquals(6, FatiguePanel.elements(busy, ctx, "Tired", "hint", "no").size());
        assertFalse(FatiguePanel.fmt(1.25).contains(","));
    }

    @Test
    void elementsPassTheClientSchema() {
        assertEquals(List.of(), ClientUiTesting.validate(FatigueHud.fatigueBar(42, InsomniaStage.TIRED, "Fatigue 42 %")));
        assertEquals(List.of(), ClientUiTesting.validate(FatigueHud.debtBar(3, "Debt 3 %")));
        assertEquals(List.of(), ClientUiTesting.validate(FatigueHud.stageToast("Tired", "text", InsomniaStage.TIRED)));
        var rested = FatigueHud.restedBar(90_000, 45 * 60_000L, "Rested");
        assertEquals(List.of(), ClientUiTesting.validate(rested));
        assertTrue(ClientUiTesting.needsExtensions(rested), "the {time} label needs client 0.9.0");
        assertTrue(rested.toJson().get("stack").getAsBoolean());
        assertTrue(FatigueHud.fatigueBar(1, InsomniaStage.RESTED, "x").toJson().get("stack").getAsBoolean());
    }

    @Test
    void statusPanelRendersForTheClient() {
        PanelContext ctx = mock(PanelContext.class);
        when(ctx.text(anyString(), any(Object[].class))).thenAnswer(i -> i.getArgument(0));
        when(ctx.text(anyString())).thenAnswer(i -> i.getArgument(0));
        var view = new FatiguePanel.View(InsomniaStage.EXHAUSTED, 61, 12, 5, 2, true);
        JsonObject doc = PanelTesting.render(Panel.page("Fatigue").children(
                FatiguePanel.elements(view, ctx, "Exhausted", "hint", "5 min").toArray(Vystorm.vystorm_core.panel.Ui.Element<?>[]::new))
                .footer(FatiguePanel.footer(ctx, "insomnia:general")));
        for (String id : List.of("state", "fatigue", "debt", "stimulant", "sleeping", "hint"))
            assertTrue(PanelTesting.find(doc, id) != null, id);
        assertEquals("panel.fatigue", PanelTesting.plain(PanelTesting.find(doc, "fatigue").get("label")));
        assertTrue(PanelTesting.find(doc, "refresh") != null);
        assertTrue(PanelTesting.find(doc, "settings").toString().contains("insomnia:general"), "admin: opens Core's settings section");
        JsonObject player = PanelTesting.render(Panel.page("Fatigue").footer(FatiguePanel.footer(ctx, null)));
        assertTrue(PanelTesting.find(player, "settings") == null);
    }
}
