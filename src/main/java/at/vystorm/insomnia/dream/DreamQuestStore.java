package at.vystorm.insomnia.dream;

import Vystorm.vystorm_core.storage.VersionedStore;
import at.vystorm.insomnia.dream.world.DreamLayout.DungeonType;
import java.util.*;

/** One non-repeatable, branching campaign per player; copy-on-write commits precede reward delivery. */
public final class DreamQuestStore {
    public enum Path { NONE, MERCY, COURAGE }
    public enum Decision { NONE, RESTORE, MASTER }
    public record Progress(int stage, Path path, Decision decision, boolean active) {
        public static final Progress NEW = new Progress(0,Path.NONE,Decision.NONE,false);
        public Progress {
            if(stage<0 || stage>5 || path==null || decision==null
                    || (stage<2)!=(path==Path.NONE) || (stage<4)!=(decision==Decision.NONE))
                throw new IllegalArgumentException("Invalid dream quest progress");
        }
        public boolean finished(){return stage==5;}
        public String ending(){
            if(!finished())return "none";
            return path==Path.MERCY ? (decision==Decision.RESTORE?"guardian":"wanderer")
                    : (decision==Decision.RESTORE?"liberator":"sovereign");
        }
        public DungeonType required(){
            return switch(stage){
                case 0 -> DungeonType.CRYSTAL_VAULT;
                case 2 -> path==Path.MERCY?DungeonType.CLOUD_RUINS:DungeonType.NIGHTMARE_KEEP;
                case 4 -> path==Path.MERCY?DungeonType.NIGHTMARE_KEEP:DungeonType.CLOUD_RUINS;
                default -> null;
            };
        }
    }
    public static final class Data { public Map<String,Progress> players=new LinkedHashMap<>(); }
    private final VersionedStore<Data> store;
    private Data data;
    public DreamQuestStore(VersionedStore<Data> store){
        this.store=Objects.requireNonNull(store);data=store.load();
        if(data.players==null || data.players.size()>100000)throw new IllegalStateException("Invalid dream quest ledger");
        data.players.forEach((id,p)->{UUID.fromString(id);new Progress(p.stage(),p.path(),p.decision(),p.active());});
    }
    public Progress progress(UUID player){return data.players.getOrDefault(player.toString(),Progress.NEW);}
    public Progress setActive(UUID player,boolean active){
        Progress old=progress(player);
        if(old.finished() || old.active()==active)return old;
        return save(player,new Progress(old.stage(),old.path(),old.decision(),active));
    }
    public Progress choose(UUID player,String choice){
        Progress old=progress(player);
        if(!old.active())return old;
        if(old.stage()==1 && (choice.equals("mercy") || choice.equals("courage")))
            return save(player,new Progress(2,Path.valueOf(choice.toUpperCase(Locale.ROOT)),Decision.NONE,true));
        if(old.stage()==3 && (choice.equals("restore") || choice.equals("master")))
            return save(player,new Progress(4,old.path(),Decision.valueOf(choice.toUpperCase(Locale.ROOT)),true));
        return old;
    }
    public boolean complete(UUID player,DungeonType dungeon){
        Objects.requireNonNull(dungeon,"dungeon");
        Progress old=progress(player);
        if(!old.active() || old.required()!=dungeon)return false;
        save(player,new Progress(old.stage()+1,old.path(),old.decision(),true));return true;
    }
    private Progress save(UUID player,Progress next){
        if(data.players.size()>=100000 && !data.players.containsKey(player.toString()))throw new IllegalStateException("Dream quest ledger full");
        Data copy=new Data();copy.players.putAll(data.players);copy.players.put(player.toString(),next);
        store.save(copy);data=copy;return next;
    }
    public static String rewardId(UUID player){return "dreamquest/v1/"+player;}
}
