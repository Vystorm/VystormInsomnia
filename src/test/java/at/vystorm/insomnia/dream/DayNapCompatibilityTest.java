package at.vystorm.insomnia.dream;

import at.vystorm.insomnia.service.SleepService;
import java.util.*;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DayNapCompatibilityTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void leavingDayNapRestoresOriginalSleepVoteBeforeWakeup(boolean original) throws Exception {
        DreamService service=mock(DreamService.class,CALLS_REAL_METHODS);
        SleepService sleep=mock(SleepService.class);
        Player player=mock(Player.class);UUID id=UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);when(player.isSleeping()).thenReturn(true);
        Class<?> napType=Class.forName(DreamService.class.getName()+"$Nap");
        var constructor=napType.getDeclaredConstructor(Location.class,Location.class,Pose.class,boolean.class,boolean.class);
        constructor.setAccessible(true);
        Map<UUID,Object> naps=new HashMap<>();naps.put(id,constructor.newInstance(null,null,Pose.STANDING,false,original));
        field(service,"naps",naps);field(service,"sleep",sleep);
        service.onNapSneak(new PlayerToggleSneakEvent(player,true));
        var ordered=inOrder(player,sleep);ordered.verify(player).setSleepingIgnored(original);
        ordered.verify(player).wakeup(false);ordered.verify(sleep).stop(player,true);
        assertTrue(naps.isEmpty());
        service.onNapSneak(new PlayerToggleSneakEvent(player,true));
        verify(player,times(1)).wakeup(false);
    }
    private static void field(Object target,String name,Object value) throws Exception {
        var field=DreamService.class.getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
}
