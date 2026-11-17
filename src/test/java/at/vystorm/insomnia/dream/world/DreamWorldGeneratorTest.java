package at.vystorm.insomnia.dream.world;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.Collectors;
import static at.vystorm.insomnia.dream.world.DreamLayout.*;
import static org.junit.jupiter.api.Assertions.*;

class DreamWorldGeneratorTest {
    @Test void deterministicLandscapeHasFiveBiomesAndNoGameplayStructures() {
        var a=landscape(42);
        assertEquals(a.layout(),landscape(42).layout());
        assertNotEquals(a.layout(),landscape(43).layout());
        assertNotEquals(a.layout().layoutHash(),landscape(42,0).layout().layoutHash());
        assertThrows(IllegalArgumentException.class,()->landscape(1,-1));
        assertThrows(IllegalArgumentException.class,()->landscape(1,101));
        assertTrue(a.layout().dungeons().isEmpty());assertTrue(a.layout().towers().isEmpty());
        assertEquals(Set.of(DreamBiome.values()),a.layout().biomes().stream().map(BiomeArea::biome).collect(Collectors.toSet()));
        assertEquals(new Bounds(-256,256,-256,256),a.layout().bounds());
    }

    @Test void naturalSpawnAndBiomeMarkersRemainSafeAcrossSeeds() {
        for(long seed:new long[]{9,123,42,773,0,-1,510231,510232,Long.MIN_VALUE,Long.MAX_VALUE,1,2,3,4,5,6,7,8,10,11,12,13,14,15,16}) {
            var g=landscape(seed);
            BlockPos spawn=g.layout().safeSpawn();
            for(int dx=-2;dx<=2;dx++) for(int dz=-2;dz<=2;dz++) {
                assertNotEquals(Material.AIR,g.blockAt(dx,spawn.y()-1,dz),"spawn support seed "+seed);
                assertEquals(Material.AIR,g.blockAt(dx,spawn.y(),dz));
                assertEquals(Material.AIR,g.blockAt(dx,spawn.y()+1,dz));
            }
            for(var b:g.layout().biomes()) {
                assertEquals(0,b.side());
                assertTrue(g.layout().bounds().contains(b.center().x(),b.center().z()));
                assertEquals(g.surfaceY(b.center().x(),b.center().z())+1,b.center().y());
            }
        }
    }

    @Test void noUpperMirrorOrOffsetWorldAndNoAuthoredStructures() {
        var g=landscape(11);
        Set<Material> buildings=Set.of(Material.STONE_BUTTON,Material.SEA_LANTERN,Material.QUARTZ_PILLAR,
                Material.WAXED_OXIDIZED_COPPER,Material.GOLD_BLOCK,Material.DIAMOND_BLOCK);
        for(int x=-240;x<=240;x+=4) for(int z=-240;z<=240;z+=4) {
            for(int y=64;y<=DreamWorldGenerator.MAX_Y+1;y++) {
                Material m=g.blockAt(x,y,z);
                if(y>DreamWorldGenerator.MAX_Y) assertEquals(Material.AIR,m,"no mirrored ceiling");
                assertFalse(buildings.contains(m),"landscape contains authored structure material "+m);
            }
            assertEquals(Material.AIR,g.blockAt(x+1024,96,z));
        }
    }

    @Test void completeDensityFieldSurvivesWithoutFillingCavities() {
        var g=landscape(510232,0,false);var t=new DreamTerrain(510232);
        int checked=0,gaps=0;
        for(var area:g.layout().biomes()) assertEquals(area.biome().ordinal(),t.biomeAt(area.center().x(),area.center().z()));
        for(int x=-240;x<=240;x+=3) for(int z=-240;z<=240;z+=3) {
            if(Math.max(Math.abs(x),Math.abs(z))<8) continue;
            int top=t.surfaceY(x,z);if(top==Integer.MIN_VALUE) continue;
            assertEquals(top,g.surfaceY(x,z));
            for(int y=64;y<=top;y++) {
                boolean solid=t.solid(x,y,z);
                assertEquals(solid,g.blockAt(x,y,z)!=Material.AIR,"density lost at "+x+","+y+","+z);
                checked++;
                if(!solid && y>=t.bottomY(x,z)) gaps++;
            }
        }
        assertTrue(checked>10000);assertTrue(gaps>100);
    }

    @Test void slopeDecorationDoesNotReplaceTheUnderlyingTerrain() {
        var bare=landscape(510232,0,false);
        var dressed=landscape(510232,100,false);
        int checked=0;
        for(int x=-220;x<=220;x+=4) for(int z=-220;z<=220;z+=4) {
            int top=bare.surfaceY(x,z);
            if(top==Integer.MIN_VALUE) continue;
            assertEquals(top,dressed.surfaceY(x,z));
            for(int y=64;y<top;y++) {
                assertEquals(bare.blockAt(x,y,z),dressed.blockAt(x,y,z),"decoration altered hillside at "+x+","+y+","+z);
                checked++;
            }
        }
        assertTrue(checked>10000);
    }

    @Test void upperTerrainCanBeDisabledWithoutChangingTheMainLandscape() {
        var base=landscape(510232,0,false,0,0);
        var upper=landscape(510232,0,false,100,0);
        int additional=0;
        for(int x=-240;x<=240;x+=3) for(int z=-240;z<=240;z+=3) {
            assertEquals(base.surfaceY(x,z),upper.surfaceY(x,z));
            for(int y=64;y<=164;y++) if(y<=base.surfaceY(x,z))
                assertEquals(base.blockAt(x,y,z),upper.blockAt(x,y,z));
            for(int y=165;y<=222;y++) if(upper.blockAt(x,y,z)!=Material.AIR) additional++;
        }
        assertTrue(additional>1000);
        assertNotEquals(base.layout().layoutHash(),upper.layout().layoutHash());
        assertFalse(upper.matchesConfiguration(510232,0,false,0,0));
    }

    @Test void naturalObjectsAreAtomicAndDoNotChangeExistingSolidGeometry() {
        var without=landscape(510232,100,false,100,65,0);
        var with=landscape(510232,100,false,100,65,65);
        assertTrue(without.objects().isEmpty());
        assertTrue(with.objects().size()>=10,"visible natural objects survive collision checks");
        assertEquals(Set.of("fallen-tree","boulder-cluster","giant-mushroom","mineral-cluster"),with.objects().stream().map(DreamObjectGenerator.Feature::kind).collect(Collectors.toSet()));
        assertNotEquals(without.layout().layoutHash(),with.layout().layoutHash());
        var upperTerrain=new DreamSkyTerrain(510232,new DreamTerrain(510232),100);
        int blocks=0;
        for(var object:with.objects()) {
            for(var v:object.voxels()) {
                assertTrue(v.y()>without.surfaceY(v.x(),v.z()),"object overwrote lower terrain");
                assertFalse(upperTerrain.solid(v.x(),v.y(),v.z()),"object overwrote upper terrain");
                String previous=without.blockAt(v.x(),v.y(),v.z()).name();
                assertFalse(previous.endsWith("_LOG") || previous.endsWith("_LEAVES") || previous.equals("MANGROVE_ROOTS"),"object overwrote vegetation");
                assertEquals(v.material(),with.blockAt(v.x(),v.y(),v.z()),"partial or overwritten object");
                assertTrue(Math.max(Math.abs(v.x()),Math.abs(v.z()))>14);
                blocks++;
            }
        }
        assertTrue(blocks>300);
        assertFalse(with.matchesConfiguration(510232,100,false,100,65,0));
    }

    @Test void chunksMatchQueriesAcrossBoundaries() {
        var g=landscape(23);
        for(int cx:new int[]{-16,-11,-1,0,1,10,15,16,64}) {
            Map<String,Material> blocks=new HashMap<>();
            var chunk=(org.bukkit.generator.ChunkGenerator.ChunkData)java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),new Class[]{org.bukkit.generator.ChunkGenerator.ChunkData.class},(p,m,a)-> {
                        if(m.getName().equals("setBlock")){blocks.put(a[0]+","+a[1]+","+a[2],(Material)a[3]);return null;}
                        if(m.getReturnType()==int.class)return 0;
                        return null;
                    });
            g.generateNoise(null,new Random(0),cx,2,chunk);
            for(int lx=0;lx<16;lx++) for(int lz=0;lz<16;lz++) for(int y=64;y<=DreamWorldGenerator.MAX_Y+1;y++)
                assertEquals(g.blockAt(cx*16+lx,y,32+lz),blocks.getOrDefault(lx+","+y+","+lz,Material.AIR));
        }
    }

    @Test void groundCoverAddsSupportedPatchesOnBothLayersWithoutChangingTerrainVolume() {
        long seed=510232;
        var bare=landscape(seed,0,false,100,0,0);
        var dressed=landscape(seed,0,false,100,65,0);
        var sky=new DreamSkyTerrain(seed,new DreamTerrain(seed),100);
        Set<Material> soils=Set.of(Material.GRASS_BLOCK,Material.MOSS_BLOCK,Material.DIRT,
                Material.COARSE_DIRT,Material.ROOTED_DIRT);
        Set<Material> newCover=Set.of(Material.FERN,Material.AZALEA,Material.FLOWERING_AZALEA,Material.MOSS_CARPET);
        int[] changed={0,0},plants={0,0};
        for(int x=-240;x<=240;x+=2) for(int z=-240;z<=240;z+=2) {
            for(int layer=0;layer<2;layer++) {
                int top=layer==0?bare.surfaceY(x,z):sky.surfaceY(x,z);
                if(top==Integer.MIN_VALUE) continue;
                assertNotEquals(Material.AIR,dressed.blockAt(x,top,z));
                if(bare.blockAt(x,top,z)!=dressed.blockAt(x,top,z)) changed[layer]++;
                Material plant=dressed.blockAt(x,top+1,z);
                if(newCover.contains(plant)) {
                    plants[layer]++;
                    assertTrue(soils.contains(dressed.blockAt(x,top,z)),"unsupported cover");
                }
                for(int y=top-4;y<top;y++) {
                    Material original=bare.blockAt(x,y,z);
                    // Existing accent roots may occupy air below thin sky shelves.
                    if(original!=Material.AIR)
                        assertEquals(original,dressed.blockAt(x,y,z),"surface dressing changed solid interior");
                }
            }
        }
        for(int layer=0;layer<2;layer++) {
            assertTrue(changed[layer]>50,"visible surface variation on layer "+layer);
            assertTrue(plants[layer]>10,"new undergrowth on layer "+layer);
        }
    }
    private static DreamWorldGenerator landscape(long seed){return landscape(seed,100,true,100,65,65);}
    private static DreamWorldGenerator landscape(long seed,int density){return landscape(seed,density,true,100,65,65);}
    private static DreamWorldGenerator landscape(long seed,int density,boolean light){return landscape(seed,density,light,100,65,65);}
    private static DreamWorldGenerator landscape(long seed,int density,boolean light,int sky,int accents){return landscape(seed,density,light,sky,accents,65);}
    private static DreamWorldGenerator landscape(long seed,int density,boolean light,int sky,int accents,int objects){return new DreamWorldGenerator(seed,density,light,sky,accents,objects,false);}
}
