package at.vystorm.insomnia.ui;

import Vystorm.vystorm_core.lang.Lang;
import at.vystorm.insomnia.service.FatigueService;
import at.vystorm.insomnia.service.RestedBonusService;
import at.vystorm.insomnia.service.SleepService;
import com.google.gson.JsonObject;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class InsomniaWebTest {
    private final InsomniaWeb web = new InsomniaWeb(mock(Plugin.class), at.vystorm.insomnia.testutil.TestCore.api().web(), new Messages(mock(Lang.class)), mock(FatigueService.class),
            mock(SleepService.class), mock(RestedBonusService.class), () -> List.of(20.0, 40.0, 60.0, 80.0));

    @Test
    void historyKeepsOneSamplePerMinuteAndIsCapped() {
        UUID player = UUID.randomUUID();
        web.record(player, new InsomniaWeb.Sample(0, 10, 1));
        web.record(player, new InsomniaWeb.Sample(30_000, 11, 1)); // dropped: less than a minute later
        web.record(player, new InsomniaWeb.Sample(60_000, 12.34, 1.26));
        JsonObject history = web.history(player);
        assertEquals(60, history.get("intervalSeconds").getAsInt());
        assertEquals(2, history.getAsJsonArray("points").size());
        assertEquals(12.3, history.getAsJsonArray("points").get(1).getAsJsonArray().get(1).getAsDouble());
        assertEquals(1.3, history.getAsJsonArray("points").get(1).getAsJsonArray().get(2).getAsDouble());
        for (int i = 2; i < InsomniaWeb.HISTORY + 20; i++) web.record(player, new InsomniaWeb.Sample(i * 60_000L, i, 0));
        assertEquals(InsomniaWeb.HISTORY, web.history(player).getAsJsonArray("points").size());
        web.forget(player);
        assertEquals(0, web.history(player).getAsJsonArray("points").size());
    }
}
