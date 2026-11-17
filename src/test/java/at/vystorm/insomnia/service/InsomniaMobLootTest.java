package at.vystorm.insomnia.service;

import Vystorm.vystorm_core.loot.LootResult;
import Vystorm.vystorm_core.loot.LootRoll;
import Vystorm.vystorm_core.loot.LootTable;
import Vystorm.vystorm_core.loot.LootTables;
import at.vystorm.insomnia.config.InsomniaConfig;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InsomniaMobLootTest {
    private static final int ROLLS = 20_000;

    private static YamlConfiguration bundledTiers() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(Objects.requireNonNull(
                InsomniaMobLootTest.class.getClassLoader().getResourceAsStream("mobs.yml")), StandardCharsets.UTF_8));
    }

    private static InsomniaMobService service(YamlConfiguration tiers, JavaPlugin plugin) {
        InsomniaConfig config = mock(InsomniaConfig.class);
        when(config.mobTiers()).thenReturn(tiers.getConfigurationSection("tiers"));
        return new InsomniaMobService(plugin, config, at.vystorm.insomnia.testutil.TestCore.bridge(), null, null, null);
    }

    @Test
    void bundledTiersBecomeOwnTablesAndRegister() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        try {
            InsomniaMobService service = service(bundledTiers(), plugin);
            assertEquals(List.of("vystorminsomnia:mobs/tier_1", "vystorminsomnia:mobs/tier_2", "vystorminsomnia:mobs/tier_3"),
                    List.copyOf(InsomniaMobLoot.tables(service.tiers()).keySet()));
            assertTrue(service.warnings().isEmpty(), service.warnings().toString());
            for (int tier : service.tierIds()) assertTrue(LootTables.exists(InsomniaMobLoot.tableId(tier)), InsomniaMobLoot.tableId(tier));
            assertTrue(service.warnings().isEmpty(), service.warnings().toString());
        } finally {
            LootTables.release(plugin);
        }
    }

    @Test
    void eachDropKeepsItsChanceAndAmounts() {
        InsomniaMobService service = service(bundledTiers(), null);
        InsomniaMobService.Tier top = service.tiers().getLast();
        LootTable table = InsomniaMobLoot.tables(service.tiers()).get(InsomniaMobLoot.tableId(top.id()));
        assertTrue(table.each());
        List<LootResult> rolls = IntStream.range(0, ROLLS)
                .mapToObj(seed -> LootRoll.of(table).source(LootTable.Source.MOB).seed(seed).roll()).toList();
        for (InsomniaMobService.DropDefinition drop : top.drops()) {
            String ref = InsomniaMobLoot.itemRef(drop.material());
            long hits = rolls.stream().filter(r -> r.hits().stream().anyMatch(h -> h.item().equals(ref))).count();
            assertEquals(drop.chance(), hits / (double) ROLLS, 0.012, ref);
            rolls.forEach(r -> r.hits().stream().filter(h -> h.item().equals(ref))
                    .forEach(h -> assertTrue(h.amount() >= drop.min() && h.amount() <= drop.max(), ref + " amount " + h.amount())));
        }
    }

    @Test
    void tierLevelRaisesRareDropsAndPlayerLuckDoesNot() {
        InsomniaMobService service = service(bundledTiers(), null);
        InsomniaMobService.Tier top = service.tiers().getLast();
        LootTable table = InsomniaMobLoot.tables(service.tiers()).get(InsomniaMobLoot.tableId(top.id()));
        InsomniaMobService.DropDefinition rarest = top.drops().stream().min(java.util.Comparator.comparingDouble(InsomniaMobService.DropDefinition::chance)).orElseThrow();
        String ref = InsomniaMobLoot.itemRef(rarest.material());
        double base = share(table, ref, 0, 0), high = share(table, ref, 60, 0), lucky = share(table, ref, 0, 20);
        assertTrue(high > base * 1.3, base + " -> " + high);
        assertEquals(base, lucky, 1e-12, "the owner's luck does not change nightmare drops");
        assertEquals(15, service.lootLevel(1));
        assertEquals(45, service.lootLevel(3));
    }

    private static double share(LootTable table, String ref, double level, double luck) {
        return IntStream.range(0, ROLLS).filter(seed -> LootRoll.of(table).mobLevel(level)
                .context(Vystorm.vystorm_core.loot.RollContext.NONE.withLuck(luck)).seed(seed).roll()
                .hits().stream().anyMatch(h -> h.item().equals(ref))).count() / (double) ROLLS;
    }

    @Test
    void configuredLootLevelPerTier() {
        YamlConfiguration tiers = bundledTiers();
        tiers.set("tiers.2.loot_level", 70);
        InsomniaMobService service = service(tiers, null);
        assertEquals(70, service.lootLevel(2));
        assertEquals(15, service.lootLevel(1));
    }

    @Test
    void largeAmountsAreCappedToOneStack() {
        LootTable table = InsomniaMobLoot.table(List.of(new InsomniaMobService.DropDefinition(Material.STRING, 1.0, 70, 90)));
        assertEquals(64, table.entries().getFirst().minAmount());
        assertEquals(64, table.entries().getFirst().maxAmount());
    }

    @Test
    void configuredLootTableWinsBlankOrUnknownUsesTheCategory() {
        YamlConfiguration tiers = bundledTiers();
        tiers.set("tiers.2.loot_table", "test:nightmare");
        tiers.set("tiers.3.loot_table", "test:missing");
        JavaPlugin other = mock(JavaPlugin.class);
        when(other.isEnabled()).thenReturn(true);
        LootTables.replace(other, Map.of("test:nightmare", new LootTable(1, 1, 1, List.of())));
        try {
            InsomniaMobService service = service(tiers, null);
            assertEquals("category:mob_drop_common", service.lootId(1));
            assertEquals("test:nightmare", service.lootId(2));
            assertEquals("category:mob_drop_common", service.lootId(3));
        } finally {
            LootTables.release(other);
        }
    }
}
