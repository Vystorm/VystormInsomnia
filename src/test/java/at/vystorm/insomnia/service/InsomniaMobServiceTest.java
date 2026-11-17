package at.vystorm.insomnia.service;

import at.vystorm.insomnia.config.InsomniaConfig;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InsomniaMobServiceTest {
    @Test
    void reloadSortsTiersAndNormalizesConfiguredDropBounds() throws Exception {
        YamlConfiguration tiers = new YamlConfiguration();
        tiers.set("tiers.10.min_fatigue", 90.0);
        tiers.set("tiers.10.entities", List.of("ZOMBIE"));
        tiers.set("tiers.10.drops.invalid.chance", 5.0);
        tiers.set("tiers.10.drops.invalid.material", "IRON_INGOT");
        tiers.set("tiers.10.drops.invalid.min", -4);
        tiers.set("tiers.10.drops.invalid.max", 0);
        tiers.set("tiers.2.min_fatigue", 70.0);
        tiers.set("tiers.2.entities", List.of("SKELETON"));
        InsomniaConfig config = mock(InsomniaConfig.class);
        when(config.mobTiers()).thenReturn(tiers.getConfigurationSection("tiers"));
        InsomniaMobService service = new InsomniaMobService(null, config, at.vystorm.insomnia.testutil.TestCore.bridge(), null, null, null);

        assertEquals(List.of(2, 10), service.tierIds());
        InsomniaMobService.Tier highest = tierFor(service, 95.0);
        InsomniaMobService.DropDefinition drop = highest.drops().getFirst();
        assertEquals(10, highest.id());
        assertEquals(1.0, drop.chance());
        assertEquals(1, drop.min());
        assertEquals(1, drop.max());
        assertEquals(2, tierFor(service, 89.9).id());
    }

    @Test
    void skippedMobEntriesAreReportedAsWarnings() {
        YamlConfiguration tiers = new YamlConfiguration();
        tiers.set("tiers.1.entities", List.of("ZOMBIE", "NOT_A_MOB"));
        tiers.set("tiers.1.drops.bad.material", "NOPE");
        tiers.set("tiers.x.entities", List.of("ZOMBIE"));
        tiers.set("tiers.2.entities", List.of("UNKNOWN"));
        InsomniaConfig config = mock(InsomniaConfig.class);
        when(config.mobTiers()).thenReturn(tiers.getConfigurationSection("tiers"));
        InsomniaMobService service = new InsomniaMobService(null, config, at.vystorm.insomnia.testutil.TestCore.bridge(), null, null, null);

        assertEquals(List.of(1), service.tierIds());
        String warnings = String.join("\n", service.warnings());
        org.junit.jupiter.api.Assertions.assertTrue(warnings.contains("NOT_A_MOB"), warnings);
        org.junit.jupiter.api.Assertions.assertTrue(warnings.contains("NOPE"), warnings);
        org.junit.jupiter.api.Assertions.assertTrue(warnings.contains("'x'"), warnings);
        org.junit.jupiter.api.Assertions.assertTrue(warnings.contains("tier 2 has no valid entities"), warnings);
    }

    @Test
    void taggedButUntrackedMobsAreOrphans() {
        InsomniaConfig config = mock(InsomniaConfig.class);
        at.vystorm.insomnia.core.CoreBridge core = mock(at.vystorm.insomnia.core.CoreBridge.class);
        InsomniaMobService service = new InsomniaMobService(null, config, core, null, null, null);
        org.bukkit.entity.Zombie tagged = mock(org.bukkit.entity.Zombie.class);
        when(tagged.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(core.getNbt(tagged, "insomnia_mob")).thenReturn("true");
        org.bukkit.entity.Zombie plain = mock(org.bukkit.entity.Zombie.class);
        when(plain.getUniqueId()).thenReturn(java.util.UUID.randomUUID());

        org.junit.jupiter.api.Assertions.assertTrue(service.isOrphan(tagged));
        org.junit.jupiter.api.Assertions.assertFalse(service.isOrphan(plain));
    }

    private static InsomniaMobService.Tier tierFor(InsomniaMobService service, double fatigue) throws Exception {
        Method method = InsomniaMobService.class.getDeclaredMethod("tierFor", double.class);
        method.setAccessible(true);
        return (InsomniaMobService.Tier) method.invoke(service, fatigue);
    }
}
