package at.vystorm.insomnia.compat.paper26;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BedLeaveOriginTest {
 @Test void onlyDirectDaylightTickIsBlocked() {
  assertTrue(BedLeaveOrigin.automatic(List.of("plugin#event", "net.minecraft.server.level.ServerPlayer#stopSleepInBed", "net.minecraft.world.entity.player.Player#tick").iterator()));
  for(String caller:List.of("net.minecraft.server.network.ServerGamePacketListenerImpl#handlePlayerCommand", "org.bukkit.craftbukkit.entity.CraftHumanEntity#wakeup", "unknown#tick"))
   assertFalse(BedLeaveOrigin.automatic(List.of("net.minecraft.server.level.ServerPlayer#stopSleepInBed",caller,"net.minecraft.world.entity.player.Player#tick").iterator()));
  assertFalse(BedLeaveOrigin.automatic(List.of("unknown#stopSleepInBed").iterator()));
 }
 @Test void missingServerCallPathAllowsWake() {
  assertFalse(BedLeaveOrigin.automaticDaylightWake());
  assertFalse(BedLeaveOrigin.automatic(List.of("net.minecraft.server.level.ServerPlayer#stopSleepInBed").iterator()));
 }
}
