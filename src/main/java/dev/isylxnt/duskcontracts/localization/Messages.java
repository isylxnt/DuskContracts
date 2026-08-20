package dev.isylxnt.duskcontracts.localization;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class Messages {
    private final JavaPlugin plugin;
    private final MiniMessage mini = MiniMessage.miniMessage();
    private final AtomicReference<YamlConfiguration> current = new AtomicReference<>();

    public Messages(JavaPlugin plugin) { this.plugin = plugin; }
    public void reload(String locale) {
        File file = new File(plugin.getDataFolder(), "lang/messages_" + locale + ".yml");
        if (!file.isFile()) file = new File(plugin.getDataFolder(), "lang/messages_en.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        if (yaml.getInt("schema-version", -1) != 1) throw new IllegalArgumentException("Invalid language schema-version in " + file.getName());
        current.set(yaml);
    }
    public Component component(String key, Map<String, ?> placeholders) {
        String raw = current.get().getString(key, "<red>Missing message: " + key);
        return parse(raw, placeholders);
    }
    public Component component(String key) { return component(key, Map.of()); }
    public void send(CommandSender sender, String key, Map<String, ?> placeholders) {
        String prefix = current.get().getString("prefix", "");
        sender.sendMessage(mini.deserialize(LegacyFormatting.normalize(prefix)).append(component(key, placeholders)));
    }
    public void send(CommandSender sender, String key) { send(sender, key, Map.of()); }
    public Component parseTemplate(String template, Map<String, ?> placeholders) {
        return parse(template, placeholders);
    }
    private Component parse(String template, Map<String, ?> placeholders) {
        TagResolver.Builder tags = TagResolver.builder();
        placeholders.forEach((name, value) -> tags.resolver(Placeholder.unparsed(name, String.valueOf(value))));
        return mini.deserialize(LegacyFormatting.normalize(template, placeholders.keySet()), tags.build());
    }
}
