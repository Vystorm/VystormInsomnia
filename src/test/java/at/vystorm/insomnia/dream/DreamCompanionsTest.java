package at.vystorm.insomnia.dream;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DreamCompanionsTest {
    private static final UUID HOST = UUID.randomUUID(), A = UUID.randomUUID(), B = UUID.randomUUID(), C = UUID.randomUUID();

    @Test
    void givenInvitation_whenAccepted_thenBothShareTheDream() {
        DreamCompanions c = new DreamCompanions();
        assertEquals("ok", c.invite(HOST, A, 0, 60_000, 3));
        assertEquals(List.of(HOST), c.invitesFor(A, 10));
        assertEquals("ok", c.accept(A, HOST, 10, 3));
        assertTrue(c.shares(HOST, A));
        assertTrue(c.shares(HOST, HOST));
        assertFalse(c.shares(HOST, B));
        assertFalse(c.shares(A, HOST), "the companion is not a host");
        assertEquals(Set.of(A), c.companionsOf(HOST));
        assertEquals(HOST, c.hostOf(A).orElseThrow());
        assertTrue(c.invitesFor(A, 10).isEmpty());
    }

    @Test
    void givenExpiredOrMissingInvitation_whenAccepting_thenRefused() {
        DreamCompanions c = new DreamCompanions();
        assertEquals("no-invite", c.accept(A, HOST, 0, 3));
        c.invite(HOST, A, 0, 60_000, 3);
        assertEquals("no-invite", c.accept(A, HOST, 60_001, 3));
    }

    @Test
    void givenRules_whenInviting_thenSelfBusyAndFullAreRefused() {
        DreamCompanions c = new DreamCompanions();
        assertEquals("self", c.invite(HOST, HOST, 0, 60_000, 3));
        c.invite(HOST, A, 0, 60_000, 1);
        c.accept(A, HOST, 0, 1);
        assertEquals("already", c.invite(HOST, A, 0, 60_000, 1));
        assertEquals("full", c.invite(HOST, B, 0, 60_000, 1));
        assertEquals("host-busy", c.invite(A, B, 0, 60_000, 3), "a companion cannot host");
        assertEquals("target-busy", c.invite(B, A, 0, 60_000, 3), "a companion cannot join a second dream");
        assertEquals("target-busy", c.invite(B, HOST, 0, 60_000, 3), "a host cannot become a companion");
    }

    @Test
    void givenBonds_whenHostLeaves_thenAllCompanionsAreReleased() {
        DreamCompanions c = new DreamCompanions();
        c.invite(HOST, A, 0, 60_000, 3); c.accept(A, HOST, 0, 3);
        c.invite(HOST, B, 0, 60_000, 3); c.accept(B, HOST, 0, 3);
        c.invite(HOST, C, 0, 60_000, 3);
        List<Map.Entry<UUID, UUID>> ended = c.leave(HOST);
        assertEquals(2, ended.size());
        assertTrue(c.hostOf(A).isEmpty());
        assertTrue(c.companionsOf(HOST).isEmpty());
        assertTrue(c.invitesFor(C, 0).isEmpty(), "open invitations of a leaving host vanish");
    }

    @Test
    void givenBond_whenCompanionLeaves_thenOnlyThatBondEnds() {
        DreamCompanions c = new DreamCompanions();
        c.invite(HOST, A, 0, 60_000, 3); c.accept(A, HOST, 0, 3);
        c.invite(HOST, B, 0, 60_000, 3); c.accept(B, HOST, 0, 3);
        assertEquals(List.of(Map.entry(HOST, A)), c.leave(A));
        assertEquals(Set.of(B), c.companionsOf(HOST));
        assertTrue(c.leave(A).isEmpty());
    }
}
