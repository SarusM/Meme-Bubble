package io.github.sarusm.meme;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import io.github.sarusm.meme.api.MemeApi;
import io.github.sarusm.meme.api.EconomyProvider;

/** {@link MemeApi} implementation — thin delegation onto the plugin's registry/data/service. */
final class ApiImpl implements MemeApi {
    private final MemePlugin plugin;

    ApiImpl(MemePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Collection<String> getEmoteIds() {
        return plugin.registry().all().stream().map(e -> e.id).toList();
    }

    @Override
    public boolean hasAccess(OfflinePlayer player, String emoteId) {
        ServerEmote emote = plugin.registry().get(emoteId);
        if (emote == null) {
            return false;
        }
        if (player instanceof Player online) {
            return plugin.service().canUse(online, emote);
        }
        return emote.defaultAccess
                || plugin.data().isOwned(player.getUniqueId(), emote.id)
                || plugin.data().isGranted(player.getUniqueId(), emote.id);
    }

    @Override
    public void grant(OfflinePlayer player, String emoteId) {
        List<ServerEmote> targets = resolve(emoteId);
        for (ServerEmote emote : targets) {
            plugin.data().addGranted(player.getUniqueId(), emote.id);
        }
        pushEntries(player, targets);
    }

    @Override
    public void revoke(OfflinePlayer player, String emoteId) {
        if ("*".equals(emoteId)) {
            plugin.data().removeAll(player.getUniqueId());
            pushEntries(player, List.copyOf(plugin.registry().all()));
            return;
        }
        List<ServerEmote> targets = resolve(emoteId);
        for (ServerEmote emote : targets) {
            plugin.data().remove(player.getUniqueId(), emote.id);
        }
        pushEntries(player, targets);
    }

    private List<ServerEmote> resolve(String emoteId) {
        if ("*".equals(emoteId)) {
            return List.copyOf(plugin.registry().all());
        }
        return java.util.stream.Stream.of(plugin.registry().get(emoteId))
                .filter(Objects::nonNull).toList();
    }

    private void pushEntries(OfflinePlayer player, List<ServerEmote> emotes) {
        if (player instanceof Player online && plugin.channel().isModded(online)) {
            for (ServerEmote emote : emotes) {
                plugin.service().sendEntry(online, emote);
            }
        }
    }

    @Override
    public Set<String> getGranted(UUID playerId) {
        return plugin.data().grantedAndOwned(playerId);
    }

    @Override
    public double getPrice(String emoteId) {
        ServerEmote emote = plugin.registry().get(emoteId);
        return emote == null ? Double.NaN : emote.price;
    }

    @Override
    public void setPrice(String emoteId, double price) {
        ServerEmote emote = plugin.registry().get(emoteId);
        if (emote != null) {
            plugin.registry().setPrice(emote.id, price);
            plugin.service().broadcastEntry(emote);
        }
    }

    @Override
    public boolean play(Player player, String emoteId) {
        return plugin.service().handlePlay(player, emoteId);
    }

    @Override
    public void stop(Player player) {
        plugin.service().handleStop(player);
    }

    @Override
    public void mute(OfflinePlayer player, long durationMillis) {
        plugin.data().mute(player.getUniqueId(), durationMillis);
        if (player instanceof Player online) {
            plugin.service().handleStop(online); // clear the current bubble immediately
        }
    }

    @Override
    public void unmute(OfflinePlayer player) {
        plugin.data().unmute(player.getUniqueId());
    }

    @Override
    public boolean isMuted(OfflinePlayer player) {
        return plugin.data().isMuted(player.getUniqueId());
    }

    @Override
    public EconomyProvider getEconomyProvider() {
        return plugin.economy();
    }

    @Override
    public void setEconomyProvider(EconomyProvider provider) {
        plugin.setEconomy(provider);
    }
}
