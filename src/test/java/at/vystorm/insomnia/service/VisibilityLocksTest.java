package at.vystorm.insomnia.service;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VisibilityLocksTest {
    private final Plugin plugin = mock(Plugin.class);
    private final VisibilityLocks locks = new VisibilityLocks(plugin);
    private final Player viewer = player();
    private final Player target = player();

    private static Player player() {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    @Test
    void targetStaysHiddenUntilTheLastReasonIsReleased() {
        locks.hide(viewer, target);
        locks.hide(viewer, target);
        verify(viewer, times(1)).hidePlayer(plugin, target);

        locks.show(viewer, target);
        verify(viewer, never()).showPlayer(plugin, target);
        assertTrue(locks.hidden(viewer, target));

        locks.show(viewer, target);
        verify(viewer).showPlayer(plugin, target);
        assertFalse(locks.hidden(viewer, target));
    }

    @Test
    void showingAPlayerThatWasNeverHiddenDoesNothing() {
        locks.show(viewer, target);
        verify(viewer, never()).showPlayer(any(Plugin.class), any(Player.class));
    }

    @Test
    void leavingPlayerIsShownAgainToEveryoneSoARelogIsNotHidden() {
        Player other = player();
        locks.hide(viewer, target);
        locks.hide(viewer, target);
        locks.hide(target, other);

        locks.forget(target, List.of(viewer, other));

        verify(viewer).showPlayer(plugin, target);
        assertFalse(locks.hidden(viewer, target));
        assertFalse(locks.hidden(target, other));
        verify(other, never()).showPlayer(any(Plugin.class), any(Player.class));
    }
}
