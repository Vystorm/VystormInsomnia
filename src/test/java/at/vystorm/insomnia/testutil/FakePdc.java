package at.vystorm.insomnia.testutil;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Map-backed PersistentDataContainer for get/set/remove/has. */
public final class FakePdc {
    public final Map<NamespacedKey, Object> values = new HashMap<>();
    public final PersistentDataContainer container = mock(PersistentDataContainer.class);

    @SuppressWarnings({"unchecked", "rawtypes"})
    public FakePdc() {
        doAnswer(call -> { values.put(call.getArgument(0), call.getArgument(2)); return null; })
                .when(container).set(any(NamespacedKey.class), any(PersistentDataType.class), any());
        doAnswer(call -> { values.remove(call.getArgument(0)); return null; })
                .when(container).remove(any(NamespacedKey.class));
        when(container.get(any(NamespacedKey.class), any(PersistentDataType.class)))
                .thenAnswer(call -> values.get(call.getArgument(0)));
        when(container.has(any(NamespacedKey.class))).thenAnswer(call -> values.containsKey(call.getArgument(0)));
    }
}
