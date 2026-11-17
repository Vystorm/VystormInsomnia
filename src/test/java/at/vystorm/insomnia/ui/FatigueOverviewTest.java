package at.vystorm.insomnia.ui;

import at.vystorm.insomnia.api.InsomniaStage;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FatigueOverviewTest {
    private static FatigueOverview.Row row(String name, double fatigue) {
        return new FatigueOverview.Row(UUID.randomUUID(), name, InsomniaStage.fromFatigue(fatigue, List.of(20.0, 40.0, 60.0, 80.0)),
                fatigue, 0.0, false, false);
    }

    @Test
    void mostTiredPlayersComeFirstAndTiesAreSortedByName() {
        List<FatigueOverview.Row> ordered = FatigueOverview.order(List.of(
                row("zoe", 10.0), row("Bob", 75.0), row("alice", 75.0), row("Carl", 92.5)));
        assertEquals(List.of("Carl", "alice", "Bob", "zoe"), ordered.stream().map(FatigueOverview.Row::name).toList());
        assertEquals(List.of(), FatigueOverview.order(List.of()));
    }

    @Test
    void quickActionsStayWithinZeroToHundred() {
        assertEquals(40.0, FatigueOverview.shifted(60.0, -FatigueOverview.STEP));
        assertEquals(0.0, FatigueOverview.shifted(5.0, -FatigueOverview.STEP));
        assertEquals(100.0, FatigueOverview.shifted(95.0, FatigueOverview.STEP));
    }

    @Test
    void quickActionsMoveTheShownValueByTheStepEvenUnderAStimulant() {
        at.vystorm.insomnia.model.PlayerFatigueState state = new at.vystorm.insomnia.model.PlayerFatigueState(UUID.randomUUID(), 0L);
        state.fatigue(70.0);
        state.stimulantSuppression(20.0); // shown 50
        state.fatigue(FatigueOverview.rawAfterShift(state, FatigueOverview.STEP));
        assertEquals(70.0, state.fatigue(), 1e-9);
        state.fatigue(FatigueOverview.rawAfterShift(state, -FatigueOverview.STEP));
        assertEquals(50.0, state.fatigue(), 1e-9);
        state.fatigue(FatigueOverview.rawAfterShift(state, -100.0));
        assertEquals(0.0, state.fatigue(), 1e-9);
    }

    @Test
    void playerMenuVisibility() {
        // enabled, showStage, viewerUses, viewerAdmin, targetLoaded
        assertTrue(FatiguePlayerMenu.visible(true, true, true, false, true));
        assertTrue(FatiguePlayerMenu.visible(true, false, true, true, true), "admins always see values");
        assertFalse(FatiguePlayerMenu.visible(true, false, true, false, true), "stage hidden from players");
        assertFalse(FatiguePlayerMenu.visible(true, true, false, false, true), "needs insomnia.use");
        assertFalse(FatiguePlayerMenu.visible(false, true, true, true, true), "switched off");
        assertFalse(FatiguePlayerMenu.visible(true, true, true, true, false), "target data still loading");
    }

    @Test
    void everyStageHasItsOwnBedColour() {
        assertEquals(InsomniaStage.values().length,
                java.util.Arrays.stream(InsomniaStage.values()).map(FatiguePlayerMenu::material).distinct().count());
        assertEquals(Material.LIME_BED, FatiguePlayerMenu.material(InsomniaStage.RESTED));
        assertEquals(Material.BLACK_BED, FatiguePlayerMenu.material(InsomniaStage.SLEEP_DEPRIVATION));
    }

    @Test
    void newTextsExistInBothLanguages() {
        var de = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("lang/de.yml"), java.nio.charset.StandardCharsets.UTF_8));
        var en = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("lang/en.yml"), java.nio.charset.StandardCharsets.UTF_8));
        List<String> keys = new java.util.ArrayList<>(List.of("overview.button", "overview.title", "overview.intro",
                "overview.empty", "overview.more", "overview.row", "overview.row_hover", "overview.sleeping", "overview.resting",
                "overview.activity", "overview.player_title", "overview.back", "overview.back_menu", "overview.chat_title",
                "overview.chat_line", "overview.action.rest", "overview.action.clear_debt", "overview.action.less",
                "overview.action.more", "player_menu.admin_name", "player_menu.admin_lore", "player_menu.name",
                "rest.started", "rest.bar", "rest.floor", "rest.stopped", "rest.status", "history.rest_started"));
        for (InsomniaStage stage : InsomniaStage.values())
            keys.add("player_menu.lore." + stage.name().toLowerCase(java.util.Locale.ROOT));
        for (String key : keys) {
            assertTrue(de.isString(key), "de.yml misses " + key);
            assertTrue(en.isString(key), "en.yml misses " + key);
        }
    }
}
