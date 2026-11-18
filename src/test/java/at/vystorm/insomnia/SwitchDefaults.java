package at.vystorm.insomnia;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import at.vystorm.insomnia.config.InsomniaConfig;

/** Stubs the 0.31.0 feature switches of a mocked config to their shipped default (on), as an unchanged config.yml has them. */
public final class SwitchDefaults {
    private SwitchDefaults() {}

    public static InsomniaConfig on(InsomniaConfig config) {
        when(config.sleepEnabled()).thenReturn(true);
        when(config.sleepQualityEnabled()).thenReturn(true);
        when(config.cozyEnabled()).thenReturn(true);
        when(config.cozyPerFurnishing()).thenAnswer(i -> config.sleepQuality("cozy_per_furnishing", 0.04));
        when(config.activityEnabled()).thenReturn(true);
        when(config.menuEnabled()).thenReturn(true);
        when(config.adminToolsEnabled()).thenReturn(true);
        when(config.clientHudEnabled()).thenReturn(true);
        when(config.webPageEnabled()).thenReturn(true);
        when(config.tutorialEnabled()).thenReturn(true);
        when(config.hallucinationEffectEnabled(anyString())).thenReturn(true);
        return config;
    }
}
