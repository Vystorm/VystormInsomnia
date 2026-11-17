package at.vystorm.insomnia.integration;

import at.vystorm.insomnia.config.InsomniaConfig;
import at.vystorm.insomnia.core.CoreBridge;
import org.bukkit.entity.Player;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CityClaimIntegrationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cityBonusToggleFollowsReloadedConfiguration(boolean initial) {
        InsomniaConfig config = mock(InsomniaConfig.class);
        when(config.cityEnabled()).thenReturn(initial);
        CityClaimIntegration integration = CityClaimIntegration.detect(null, mock(CoreBridge.class), config);
        assertEquals(initial, integration.available());
        when(config.cityEnabled()).thenReturn(!initial);
        assertEquals(!initial, integration.available());
        when(config.cityEnabled()).thenReturn(false);
        Player player = mock(Player.class);
        assertEquals(1.0, integration.sleepQualityMultiplier(player));
        verifyNoInteractions(player);
    }
}
