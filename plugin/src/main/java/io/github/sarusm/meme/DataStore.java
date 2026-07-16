package io.github.sarusm.meme;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Persistent per-player state ({@code plugins/Meme/data.yml}): purchased emotes, admin/API grants,
 * and admin mutes (with optional expiry). Loaded on enable, saved on change and on disable.
 */
public final class DataStore {
    private final MemePlugin plugin;
    private final Map<UUID, Set<String>> owned = new HashMap<>();
    private final Map<UUID, Set<String>> granted = new HashMap<>();
    /** UUID -> mute expiry epoch millis; 0 = until unmuted. */
    private final Map<UUID, Long> muted = new HashMap<>();

    public DataStore(MemePlugin plugin) {
        this.plugin = plugin;
    }

    private File file() {
        return new File(plugin.getDataFolder(), "data.yml");
    }

    public void load() {
        owned.clear();
        granted.clear();
        muted.clear();
        if (!file().exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file());
        readSets(yml, "owned", owned);
        readSets(yml, "granted", granted);
        ConfigurationSection m = yml.getConfigurationSection("muted");
        if (m != null) {
            for (String key : m.getKeys(false)) {
                try {
                    muted.put(UUID.fromString(key), m.getLong(key));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    private static void readSets(YamlConfiguration yml, String root, Map<UUID, Set<String>> into) {
        ConfigurationSection section = yml.getConfigurationSection(root);
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                List<String> ids = section.getStringList(key);
                if (!ids.isEmpty()) {
                    into.put(UUID.fromString(key), new HashSet<>(ids));
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        owned.forEach((id, set) -> yml.set("owned." + id, List.copyOf(set)));
        granted.forEach((id, set) -> yml.set("granted." + id, List.copyOf(set)));
        muted.forEach((id, until) -> yml.set("muted." + id, until));
        try {
            yml.save(file());
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save data.yml: " + e);
        }
    }

    public boolean isOwned(UUID player, String emoteId) {
        Set<String> set = owned.get(player);
        return set != null && set.contains(emoteId.toLowerCase(Locale.ROOT));
    }

    public boolean isGranted(UUID player, String emoteId) {
        Set<String> set = granted.get(player);
        return set != null && set.contains(emoteId.toLowerCase(Locale.ROOT));
    }

    public void addOwned(UUID player, String emoteId) {
        owned.computeIfAbsent(player, k -> new HashSet<>()).add(emoteId.toLowerCase(Locale.ROOT));
        save();
    }

    public void addGranted(UUID player, String emoteId) {
        granted.computeIfAbsent(player, k -> new HashSet<>()).add(emoteId.toLowerCase(Locale.ROOT));
        save();
    }

    public void remove(UUID player, String emoteId) {
        String id = emoteId.toLowerCase(Locale.ROOT);
        Set<String> o = owned.get(player);
        if (o != null) {
            o.remove(id);
        }
        Set<String> g = granted.get(player);
        if (g != null) {
            g.remove(id);
        }
        save();
    }

    public void removeAll(UUID player) {
        owned.remove(player);
        granted.remove(player);
        save();
    }

    public Set<String> grantedAndOwned(UUID player) {
        Set<String> out = new HashSet<>();
        Set<String> o = owned.get(player);
        if (o != null) {
            out.addAll(o);
        }
        Set<String> g = granted.get(player);
        if (g != null) {
            out.addAll(g);
        }
        return out;
    }

    /** 0 = not muted, {@link Long#MAX_VALUE} = permanent, else expiry epoch millis (auto-expunged). */
    public boolean isMuted(UUID player) {
        Long until = muted.get(player);
        if (until == null) {
            return false;
        }
        if (until != 0L && until < System.currentTimeMillis()) {
            muted.remove(player);
            save();
            return false;
        }
        return true;
    }

    public void mute(UUID player, long durationMillis) {
        muted.put(player, durationMillis <= 0 ? 0L : System.currentTimeMillis() + durationMillis);
        save();
    }

    public void unmute(UUID player) {
        muted.remove(player);
        save();
    }
}
