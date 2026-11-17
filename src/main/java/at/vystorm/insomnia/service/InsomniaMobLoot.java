package at.vystorm.insomnia.service;

import Vystorm.vystorm_core.loot.LootTable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Nightmare mob drops as Vystorm Core weighted loot (Core 0.44.0, capability {@code loot-rolls}). Each tier of
 * {@code mobs.yml} becomes the own table {@code vystorminsomnia:mobs/tier_<id>} in {@code mode: each}: every drop keeps
 * its own {@code chance} and {@code min..max}, so at level 0 the odds stay exactly as before; the rank follows the chance
 * so the tier's loot level (Core mob loot: level luck only, never the player's) makes rarer drops likelier. Kills roll
 * {@code category:mob_drop_common} with that table as {@code self}, or the tier's {@code loot_table} override.
 */
public final class InsomniaMobLoot {
    public static final String NAMESPACE = "vystorminsomnia";
    public static final String CATEGORY = "category:mob_drop_common";
    /** Largest stack a loot entry can carry; larger {@code max} values are capped here. */
    public static final int MAX_AMOUNT = 64;

    private InsomniaMobLoot() {}

    /** Rarity from the drop chance (100 % = 0, 18 % = 8200): the tier's loot level raises the rarer drops. */
    static int rank(double chance) { return (int) Math.round((1.0 - Math.max(0.0, Math.min(1.0, chance))) * 10000.0); }

    /** Marks a nightmare mob as rolled by Insomnia (no SpawnDirector / Items roll). */
    public static void claim(Vystorm.vystorm_core.loot.MobLootInterface mobLoot, org.bukkit.plugin.Plugin plugin, org.bukkit.entity.Entity entity) {
        if (plugin == null) return;
        try { mobLoot.claim(plugin, entity); } catch (RuntimeException ignored) { }
    }

    /** Own table id of a tier. */
    public static String tableId(int tier) {
        return NAMESPACE + ":mobs/tier_" + tier;
    }

    /** Item ref of a vanilla drop material. */
    public static String itemRef(org.bukkit.Material material) {
        return "minecraft:" + material.name().toLowerCase(Locale.ROOT);
    }

    /** One own table per tier, converted from the tier's drop list. */
    public static Map<String, LootTable> tables(List<InsomniaMobService.Tier> tiers) {
        Map<String, LootTable> tables = new LinkedHashMap<>();
        for (InsomniaMobService.Tier tier : tiers) tables.put(tableId(tier.id()), table(tier.drops()));
        return tables;
    }

    /** Independent drops: each entry is checked once per kill with its own chance. */
    static LootTable table(List<InsomniaMobService.DropDefinition> drops) {
        List<LootTable.Entry> entries = new ArrayList<>();
        for (InsomniaMobService.DropDefinition drop : drops) {
            int min = Math.max(1, Math.min(MAX_AMOUNT, drop.min()));
            int max = Math.max(min, Math.min(MAX_AMOUNT, drop.max()));
            double chance = Math.max(0.0, Math.min(1.0, drop.chance()));
            int rank = rank(chance);
            entries.add(new LootTable.Entry(itemRef(drop.material()), min, max, rank, rank,
                    LootTable.Options.NONE.withChance(chance)));
        }
        return new LootTable(1, 1, 1, entries, true);
    }
}
