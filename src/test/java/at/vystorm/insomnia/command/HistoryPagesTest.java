package at.vystorm.insomnia.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.service.FatigueService;
import at.vystorm.insomnia.service.PlayerInsightHistory.Entry;
import at.vystorm.insomnia.ui.Messages;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class HistoryPagesTest {
    @Test void secondPageShowsOlderEntriesWithoutChangingHistory() {
        var player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.hasPermission(InsomniaCommand.USE)).thenReturn(true);
        var fatigue = mock(FatigueService.class);
        when(fatigue.ready(id)).thenReturn(true);
        List<Entry> entries = IntStream.range(0, 17).mapToObj(n -> new Entry(n * 60_000L, n, n, "sample")).toList();
        when(fatigue.recentHistory(id)).thenReturn(entries);
        var config = mock(InsomniaConfig.class);
        var messages = mock(Messages.class);
        when(config.messages()).thenReturn(messages);
        var command = new InsomniaCommand(config, fatigue, null, null, null, null, () -> {}, null);
        command.execute(player, "insomnia", "history", "2");
        verify(messages).text(player, "history.page", "page", 2, "pages", 3);
        verify(messages).text(player, "history.entry", "at", "1970-01-01T00:08:00Z", "fatigue", "8.0", "debt", "8.0", "event", null);
        verify(fatigue).ready(id);
        verify(fatigue).recentHistory(id);
        verifyNoMoreInteractions(fatigue);
        assertEquals(17, entries.size());
    }

    @Test void bedSearchDoesNotReadBlocksInUnloadedChunks() {
        var player = mock(Player.class);
        var world = mock(World.class);
        when(player.getLocation()).thenReturn(new Location(world, 15, 64, 15));
        assertNull(InsomniaCommand.nearbyBed(player));
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        verify(world, never()).getChunkAt(anyInt(), anyInt());
    }
}
