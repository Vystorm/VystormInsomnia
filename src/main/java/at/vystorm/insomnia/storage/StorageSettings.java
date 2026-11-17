package at.vystorm.insomnia.storage;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** {@code storage.*} from config.yml. Read once at startup; a change needs a restart. */
public record StorageSettings(Type type, String tablePrefix, String serverId, int claimWaitSeconds) {
    public enum Type { YAML, MYSQL }

    public static final String DEFAULT_PREFIX = "vinsom";
    public static final String DEFAULT_SERVER_ID = "default";
    private static final Pattern PREFIX = Pattern.compile("[a-z][a-z0-9]{1,15}");
    private static final Pattern SERVER_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    public boolean mysql() { return type == Type.MYSQL; }

    public static StorageSettings from(ConfigurationSection config) {
        String type = config.getString("storage.type", "yaml").trim().toLowerCase(Locale.ROOT);
        return new StorageSettings(type.equals("mysql") ? Type.MYSQL : Type.YAML,
                config.getString("storage.table-prefix", DEFAULT_PREFIX).trim(),
                config.getString("storage.server-id", DEFAULT_SERVER_ID).trim(),
                config.getInt("storage.claim-wait-seconds", 10));
    }

    public static List<String> validate(ConfigurationSection config) {
        List<String> errors = new ArrayList<>();
        String type = config.getString("storage.type", "yaml").trim().toLowerCase(Locale.ROOT);
        if (!type.equals("yaml") && !type.equals("mysql")) errors.add("storage.type must be yaml or mysql");
        if (!PREFIX.matcher(config.getString("storage.table-prefix", DEFAULT_PREFIX).trim()).matches()) {
            errors.add("storage.table-prefix must be 2-16 characters [a-z][a-z0-9]*");
        }
        if (!SERVER_ID.matcher(config.getString("storage.server-id", DEFAULT_SERVER_ID).trim()).matches()) {
            errors.add("storage.server-id must be 1-64 characters A-Z a-z 0-9 _ . -");
        }
        int wait = config.getInt("storage.claim-wait-seconds", 10);
        if (wait < 1 || wait > 120) errors.add("storage.claim-wait-seconds must be between 1 and 120");
        return errors;
    }
}
