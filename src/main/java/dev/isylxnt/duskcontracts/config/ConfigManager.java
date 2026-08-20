package dev.isylxnt.duskcontracts.config;

import dev.isylxnt.duskcontracts.domain.MatchMode;
import dev.isylxnt.duskcontracts.domain.Money;
import dev.isylxnt.duskcontracts.domain.Parsers;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class ConfigManager {
    private static final Set<String> PREVIOUS_DEFAULT_PREFIXES = Set.of(
            "<dark_gray>[<gold>DuskContracts</gold>]</dark_gray> ",
            "&#9863E7⏵ DuskTrade &8| ");
    private static final List<String> RESOURCES = List.of("config.yml", "storage.yml", "menus.yml", "item-rules.yml",
            "lang/messages_en.yml", "lang/messages_es.yml");
    private final JavaPlugin plugin;
    private final AtomicReference<AppConfig> current = new AtomicReference<>();
    private final AtomicReference<StorageConfig> storage = new AtomicReference<>();

    public ConfigManager(JavaPlugin plugin) { this.plugin = plugin; }

    public void initialize() throws ConfigurationException {
        for (String resource : RESOURCES) installOrMerge(resource);
        reload();
        new File(plugin.getDataFolder(), "recovery").mkdirs();
    }

    public synchronized void reload() throws ConfigurationException {
        AppConfig next = parseApp(load("config.yml"));
        StorageConfig nextStorage = parseStorage(load("storage.yml"));
        load("menus.yml"); load("item-rules.yml"); load("lang/messages_en.yml"); load("lang/messages_es.yml");
        StorageConfig activeStorage=storage.get();
        if(activeStorage!=null&&!activeStorage.equals(nextStorage))throw new ConfigurationException("storage.yml contains changes that require a server restart; the active configuration was not changed.");
        current.set(next);
        if(activeStorage==null)storage.set(nextStorage);
    }

    public AppConfig get() { return current.get(); }
    public StorageConfig storage() { return storage.get(); }

    private void installOrMerge(String path) throws ConfigurationException {
        File target = new File(plugin.getDataFolder(), path);
        if (!target.exists()) {
            target.getParentFile().mkdirs();
            plugin.saveResource(path, false);
            return;
        }
        YamlConfiguration existing = commentedYaml(target, path);
        var stream = plugin.getResource(path);
        if (stream == null) throw new ConfigurationException("Missing bundled resource " + path);
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.options().parseComments(true);
        try (var reader = new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)) {
            defaults.load(reader);
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException ex) {
            throw new ConfigurationException("Cannot read bundled " + path, ex);
        }
        boolean changed = false;
        if (path.startsWith("lang/") && PREVIOUS_DEFAULT_PREFIXES.contains(existing.getString("prefix"))) {
            existing.set("prefix", defaults.getString("prefix"));
            changed = true;
        }
        if (path.equals("lang/messages_es.yml") && "<green>Se guardaron {stacks} pila(s) como recompensa del contrato."
                .equals(existing.getString("wizard.reward-items-selected"))) {
            existing.set("wizard.reward-items-selected", defaults.getString("wizard.reward-items-selected"));
            changed = true;
        }
        if (path.equals("lang/messages_en.yml") && "<green>Saved {stacks} item stack(s) as the contract reward."
                .equals(existing.getString("wizard.reward-items-selected"))) {
            existing.set("wizard.reward-items-selected", defaults.getString("wizard.reward-items-selected"));
            changed = true;
        }
        for (String key : defaults.getKeys(true)) {
            if (!defaults.isConfigurationSection(key) && !existing.contains(key)) {
                existing.set(key, defaults.get(key));
                changed = true;
            }
            if (existing.getComments(key).isEmpty() && !defaults.getComments(key).isEmpty()) {
                existing.setComments(key, defaults.getComments(key));
                changed = true;
            }
            if (existing.getInlineComments(key).isEmpty() && !defaults.getInlineComments(key).isEmpty()) {
                existing.setInlineComments(key, defaults.getInlineComments(key));
                changed = true;
            }
        }
        if (changed) try { existing.save(target); }
        catch (IOException ex) { throw new ConfigurationException("Cannot merge " + path, ex); }
    }

    private static YamlConfiguration commentedYaml(File file, String path) throws ConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        try { yaml.load(file); }
        catch (IOException | org.bukkit.configuration.InvalidConfigurationException ex) {
            throw new ConfigurationException("Cannot read existing " + path, ex);
        }
        return yaml;
    }

    private YamlConfiguration load(String path) throws ConfigurationException {
        File file = new File(plugin.getDataFolder(), path);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        if (yaml.getInt("schema-version", -1) != 1)
            throw error(path, "schema-version", yaml.get("schema-version"), "the integer 1");
        return yaml;
    }

    private AppConfig parseApp(YamlConfiguration y) throws ConfigurationException {
        try {
            int maxActive = positiveInt(y, "contracts.max-active-per-player");
            long maxAmount = positiveLong(y, "contracts.max-request-amount");
            int places = rangeInt(y, "money.decimal-places", 0, 8);
            long min = Money.parse(requiredString(y, "money.minimum-reward"), places).minorUnits();
            long max = Money.parse(requiredString(y, "money.maximum-reward"), places).minorUnits();
            if (max < min) throw error("config.yml", "money.maximum-reward", max, "at least money.minimum-reward");
            MatchMode defaultMode = enumValue(y, "matching.default-mode", MatchMode.class);
            Set<MatchMode> modes = EnumSet.noneOf(MatchMode.class);
            for (String mode : y.getStringList("matching.enabled-modes")) modes.add(MatchMode.valueOf(mode.toUpperCase(Locale.ROOT)));
            if (modes.isEmpty() || !modes.contains(defaultMode)) throw error("config.yml", "matching.enabled-modes", modes, "a list containing the default mode");
            List<Duration> durations = new ArrayList<>();
            for (String d : y.getStringList("contracts.allowed-durations")) durations.add(Parsers.duration(d));
            if (durations.isEmpty()) throw error("config.yml", "contracts.allowed-durations", durations, "a non-empty duration list");
            return new AppConfig(requiredString(y, "locale"), maxActive,
                    Parsers.duration(requiredString(y, "contracts.creation-cooldown")),
                    Parsers.duration(requiredString(y, "contracts.default-duration")), durations, maxAmount,
                    y.getBoolean("contracts.allow-private-contracts"), y.getBoolean("contracts.allow-own-fulfillment"),
                    defaultMode, modes, y.getBoolean("partial-fulfillment.enabled"),
                    positiveLong(y, "partial-fulfillment.minimum-contribution"), y.getBoolean("money.enabled"), places,
                    min, max, Parsers.duration(requiredString(y, "expiration.sweep-interval")),
                    positiveInt(y, "expiration.process-batch-size"), positiveInt(y, "security.max-serialized-item-bytes"),
                    Parsers.duration(requiredString(y, "security.operation-timeout")),
                    Parsers.duration(requiredString(y, "security.session-timeout")),
                    positiveInt(y, "security.max-actions-per-second"),
                    Parsers.duration(requiredString(y, "assassination.repeat-kill-cooldown")),
                    positiveInt(y, "logging.audit-retention-days"),
                    y.getBoolean("logging.verbose-startup-report"));
        } catch (ConfigurationException ex) { throw ex; }
        catch (RuntimeException ex) { throw new ConfigurationException("config.yml contains an invalid value: " + ex.getMessage(), ex); }
    }

    private StorageConfig parseStorage(YamlConfiguration y) throws ConfigurationException {
        try {
            StorageConfig.Type type = enumValue(y, "type", StorageConfig.Type.class);
            String prefix = type.name().toLowerCase(Locale.ROOT) + ".";
            return new StorageConfig(type, y.getString("sqlite.file", "contracts.db"), y.getString(prefix + "host", "localhost"),
                    y.getInt(prefix + "port", type == StorageConfig.Type.MYSQL ? 3306 : 3306),
                    y.getString(prefix + "database", "duskcontracts"), y.getString(prefix + "username", "root"),
                    y.getString(prefix + "password", ""), storageTlsMode(y.getString(prefix + "tls-mode", "PREFERRED")), positiveInt(y, "pool.maximum-size"),
                    positiveLong(y, "pool.connection-timeout-ms"), positiveLong(y, "pool.validation-timeout-ms"));
        } catch (ConfigurationException ex) { throw ex; }
        catch (RuntimeException ex) { throw new ConfigurationException("storage.yml contains an invalid value: " + ex.getMessage(), ex); }
    }

    private static String requiredString(YamlConfiguration y, String path) throws ConfigurationException {
        String value = y.getString(path);
        if (value == null || value.isBlank()) throw error("config.yml", path, value, "a non-empty string");
        return value;
    }
    private static int positiveInt(YamlConfiguration y, String path) throws ConfigurationException {
        if (!y.isInt(path) || y.getInt(path) < 1) throw error("config.yml", path, y.get(path), "an integer greater than or equal to 1");
        return y.getInt(path);
    }
    private static int rangeInt(YamlConfiguration y, String path, int min, int max) throws ConfigurationException {
        if (!y.isInt(path) || y.getInt(path) < min || y.getInt(path) > max) throw error("config.yml", path, y.get(path), "an integer from " + min + " to " + max);
        return y.getInt(path);
    }
    private static long positiveLong(YamlConfiguration y, String path) throws ConfigurationException {
        if (!y.isLong(path) && !y.isInt(path)) throw error("config.yml", path, y.get(path), "a positive integer");
        long value = y.getLong(path);
        if (value < 1) throw error("config.yml", path, value, "a positive integer");
        return value;
    }
    private static <E extends Enum<E>> E enumValue(YamlConfiguration y, String path, Class<E> type) throws ConfigurationException {
        try { return Enum.valueOf(type, requiredString(y, path).toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { throw error("config.yml", path, y.get(path), "one of " + List.of(type.getEnumConstants())); }
    }
    private static StorageConfig.TlsMode storageTlsMode(String value) throws ConfigurationException {
        try { return StorageConfig.TlsMode.valueOf(value.toUpperCase(Locale.ROOT)); }
        catch (RuntimeException ex) { throw new ConfigurationException("storage.yml: tls-mode must be one of " + List.of(StorageConfig.TlsMode.values()) + "; received " + value); }
    }
    private static ConfigurationException error(String file, String path, Object got, String expected) {
        return new ConfigurationException(file + ": " + path + " must be " + expected + "; received " + got + ". The previous configuration remains active.");
    }
}
