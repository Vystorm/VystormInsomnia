package at.vystorm.insomnia;

import Vystorm.vystorm_core.CoreApi;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreVersionCheckTest {
    @Test
    void coreBeforeTheDocumentedPlatformCompatibilityMinimumIsRejected() {
        try (var core = org.mockito.Mockito.mockStatic(CoreApi.class)) {
            core.when(() -> CoreApi.atLeast(VystormInsomniaPlugin.MIN_CORE_VERSION)).thenReturn(false);
            assertFalse(VystormInsomniaPlugin.requiredCoreApiAvailable());
        }
    }

    private final ClassLoader loader = getClass().getClassLoader();

    @Test
    void coreWithoutCompatibilityReleaseIsRejected() {
        try (var core = org.mockito.Mockito.mockStatic(CoreApi.class)) {
            core.when(() -> CoreApi.atLeast(VystormInsomniaPlugin.MIN_CORE_VERSION)).thenReturn(false);
            assertFalse(VystormInsomniaPlugin.requiredCoreApiAvailable());
        }
    }

    @Test
    void buildCoreIsNewEnough() {
        assertTrue(VystormInsomniaPlugin.requiredCoreApiAvailable());
    }

    @Test
    void missingCoreClassIsReportedInsteadOfThrown() {
        assertFalse(VystormInsomniaPlugin.hasClass("Vystorm.vystorm_core.DoesNotExist", loader));
    }

    /** Every Core class the plugin imports without a guard must ship with the Core it is built against. */
    @Test
    void unguardedCoreApisArePresent() {
        for (String name : new String[] {
                "Vystorm.vystorm_core.API",
                "Vystorm.vystorm_core.CoreApi",
                "Vystorm.vystorm_core._uiMethods.menu.MenuCategory",
                "Vystorm.vystorm_core.compat.ServerCompat",
                "Vystorm.vystorm_core.compat.dialog.DialogRequest",
                "Vystorm.vystorm_core.compat.dialog.DialogButton",
                "Vystorm.vystorm_core._coreMethods.dataStorage.dataType.DataEntry",
                "Vystorm.vystorm_core._coreMethods.dataStorage.methods.DataEncoder"}) {
            assertTrue(VystormInsomniaPlugin.hasClass(name, loader), name);
        }
    }
}
