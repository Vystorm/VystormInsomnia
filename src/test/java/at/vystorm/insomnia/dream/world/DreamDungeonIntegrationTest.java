package at.vystorm.insomnia.dream.world;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import java.util.*;
import static at.vystorm.insomnia.dream.world.DreamLayout.*;
import static org.junit.jupiter.api.Assertions.*;

class DreamDungeonIntegrationTest {
    @Test void allSitesAreReachableAndWalkableWithFullLandscapeDecoration() {
        for(long seed:new long[]{510231,510232,42,0,-1,Long.MIN_VALUE,Long.MAX_VALUE}) {
            var g=new DreamWorldGenerator(seed);
            assertEquals(Set.of(DungeonType.values()),new HashSet<>(g.layout().dungeons().stream().map(Dungeon::type).toList()));
            assertTrue(g.layout().towers().isEmpty());
            Set<BlockPos> objectives=new HashSet<>();
            for(var site:g.dungeonSites()) {
                Dungeon d=site.plan().dungeon();
                objectives.add(d.entry());objectives.add(d.reward());objectives.add(d.encounterSpawn());objectives.addAll(d.puzzleAnchors());
                BlockPos previous=g.layout().safeSpawn();
                for(var p:site.trail()) {
                    assertEquals(1,Math.abs(p.x()-previous.x())+Math.abs(p.z()-previous.z()));
                    assertTrue(Math.abs(p.y()-previous.y())<=1);
                    assertStandable(g,p);
                    // Jumping one step also needs clearance over the lower step.
                    assertEquals(Material.AIR,g.blockAt(p.x(),p.y()+2,p.z()));
                    previous=p;
                }
                assertEquals(site.plan().approach(),previous);
                assertTrue(d.bounds().minX()>=-238&&d.bounds().maxX()<=238);
                assertTrue(d.bounds().minZ()>=-238&&d.bounds().maxZ()<=238);
                for(var other:g.layout().dungeons()) if(other!=d)
                    assertFalse(d.bounds().contains(other.entry().x(),other.entry().z()));
                // Walk actual final geometry at dungeon height, not just shape proposals.
                Set<BlockPos> reached=new HashSet<>();ArrayDeque<BlockPos> pending=new ArrayDeque<>();
                pending.add(previous);reached.add(previous);
                while(!pending.isEmpty()) {
                    var p=pending.removeFirst();
                    for(int dir=0;dir<4;dir++) {
                        var next=new BlockPos(p.x()+(dir==0?-1:dir==1?1:0),p.y(),p.z()+(dir==2?-1:dir==3?1:0));
                        if(next.x()<d.bounds().minX()-2||next.x()>d.bounds().maxX()+2||next.z()<d.bounds().minZ()-2||next.z()>d.bounds().maxZ()+2)continue;
                        if(standable(g,next)&&reached.add(next))pending.add(next);
                    }
                }
                for(var p:objectives) if(d.bounds().contains(p.x(),p.z()))
                    assertTrue(reached.contains(p),"unreachable objective "+d.id()+" seed "+seed+" at "+p);
                if(d.type()==DungeonType.NIGHTMARE_KEEP)for(var anchor:d.puzzleAnchors())
                    for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)
                        assertStandable(g,new BlockPos(anchor.x()+dx,anchor.y(),anchor.z()+dz));
            }
        }
    }
    @Test void disablingDungeonsPreservesLandscapeAndInvalidatesTheLayout() {
        var disabled=new DreamWorldGenerator(510232,100,true,100,65,65,false);
        var enabled=new DreamWorldGenerator(510232);
        assertTrue(disabled.layout().dungeons().isEmpty());
        assertNotEquals(disabled.layout().layoutHash(),enabled.layout().layoutHash());
        assertFalse(enabled.matchesConfiguration(510232,100,true,100,65,65,false));
        assertTrue(enabled.matchesConfiguration(510232,100,true,100,65,65,true));
        assertEquals(disabled.layout().safeSpawn(),enabled.layout().safeSpawn());
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)
            assertStandable(enabled,new BlockPos(x,enabled.layout().safeSpawn().y(),z));
    }
    private static boolean standable(DreamWorldGenerator g,BlockPos p) {
        return g.blockAt(p.x(),p.y()-1,p.z())!=Material.AIR
                &&g.blockAt(p.x(),p.y(),p.z())==Material.AIR&&g.blockAt(p.x(),p.y()+1,p.z())==Material.AIR;
    }
    private static void assertStandable(DreamWorldGenerator g,BlockPos p){assertTrue(standable(g,p),"blocked feet "+p);}
}
