package at.vystorm.insomnia.dream;
import Vystorm.vystorm_core.storage.VersionedStore;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static at.vystorm.insomnia.dream.world.DreamLayout.DungeonType.*;

class DreamQuestStoreTest {
    static class Memory implements VersionedStore.Backend {
        String raw;boolean fail;int writes;
        public String read(){return raw;}public void write(String s){if(fail)throw new IllegalStateException("disk full");raw=s;writes++;}
        DreamQuestStore open(){return new DreamQuestStore(new VersionedStore<>(this,DreamQuestStore.Data.class,1,Map.of(),DreamQuestStore.Data::new,null));}
    }
    @Test void allFourBranchesHaveDistinctEndingsAndOneStableReceipt(){
        Set<String> endings=new HashSet<>();
        for(String path:List.of("mercy","courage"))for(String decision:List.of("restore","master")){
            Memory disk=new Memory();DreamQuestStore store=disk.open();UUID player=UUID.randomUUID();
            store.setActive(player,true);assertTrue(store.complete(player,CRYSTAL_VAULT));
            assertFalse(store.complete(player,CRYSTAL_VAULT));store.choose(player,path);
            assertTrue(store.complete(player,path.equals("mercy")?CLOUD_RUINS:NIGHTMARE_KEEP));
            store=disk.open();assertEquals(3,store.progress(player).stage());store.choose(player,decision);
            assertTrue(store.complete(player,path.equals("mercy")?NIGHTMARE_KEEP:CLOUD_RUINS));
            endings.add(disk.open().progress(player).ending());int writes=disk.writes;
            assertFalse(store.complete(player,CRYSTAL_VAULT));store.setActive(player,false);store.choose(player,"mercy");
            assertEquals(writes,disk.writes);assertEquals("dreamquest/v1/"+player,DreamQuestStore.rewardId(player));
        }
        assertEquals(Set.of("guardian","wanderer","liberator","sovereign"),endings);
    }
    @Test void wrongOrderPausedAndAnotherPlayerDoNotProgress(){
        Memory disk=new Memory();DreamQuestStore store=disk.open();UUID player=UUID.randomUUID(),other=UUID.randomUUID();
        assertFalse(store.complete(player,CRYSTAL_VAULT));store.setActive(player,true);
        assertFalse(store.complete(player,NIGHTMARE_KEEP));assertEquals(0,store.choose(player,"master").stage());
        store.setActive(player,false);assertFalse(store.complete(player,CRYSTAL_VAULT));
        store=disk.open();assertFalse(store.progress(player).active());store.setActive(player,true);
        assertTrue(store.complete(player,CRYSTAL_VAULT));assertEquals(DreamQuestStore.Progress.NEW,store.progress(other));
    }
    @Test void failedWriteRetainsPreviousStateAndCanRetryExactlyOnce(){
        Memory disk=new Memory();DreamQuestStore store=disk.open();UUID player=UUID.randomUUID();store.setActive(player,true);
        String raw=disk.raw;disk.fail=true;assertThrows(IllegalStateException.class,()->store.complete(player,CRYSTAL_VAULT));
        assertEquals(0,store.progress(player).stage());assertEquals(raw,disk.raw);
        disk.fail=false;assertTrue(store.complete(player,CRYSTAL_VAULT));assertFalse(store.complete(player,CRYSTAL_VAULT));
    }
    @Test void invalidFutureOrCorruptStoreIsNeverReplaced(){
        Memory disk=new Memory();disk.raw="corrupt";assertThrows(RuntimeException.class,disk::open);assertEquals("corrupt",disk.raw);
        disk.raw="{\"schema\":99,\"data\":{}}";assertThrows(RuntimeException.class,disk::open);assertEquals(0,disk.writes);
    }
}
