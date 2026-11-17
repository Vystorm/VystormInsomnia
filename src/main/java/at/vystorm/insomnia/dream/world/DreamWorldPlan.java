package at.vystorm.insomnia.dream.world;

import java.util.ArrayList;
import java.util.List;

/** Land survey only: biome markers never define or sculpt the terrain. */
final class DreamWorldPlan {
    record Point(int x, int z, int floor) {}
    record BiomeSite(int biome, Point point) {}
    final List<BiomeSite> biomes;

    DreamWorldPlan(DreamTerrain terrain) {
        List<BiomeSite> regions=new ArrayList<>();
        for(int biome=0;biome<5;biome++) {
            Point best=null;int support=-1;
            for(int x=-224;x<=224;x+=4) for(int z=-224;z<=224;z+=4) {
                int top=terrain.surfaceY(x,z);
                if(top==Integer.MIN_VALUE || terrain.biomeAt(x,z)!=biome) continue;
                int score=0;
                for(int dx=-16;dx<=16;dx+=8) for(int dz=-16;dz<=16;dz+=8)
                    if(terrain.surfaceY(x+dx,z+dz)!=Integer.MIN_VALUE && terrain.biomeAt(x+dx,z+dz)==biome) score++;
                if(score>support) {support=score;best=new Point(x,z,top);}
            }
            if(best==null) throw new IllegalStateException("Generated terrain has no biome "+biome);
            regions.add(new BiomeSite(biome,best));
        }
        biomes=List.copyOf(regions);
    }
}
