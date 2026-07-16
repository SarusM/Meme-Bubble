package io.github.sarusm.meme.net;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

import io.github.sarusm.meme.MemePlugin;
import io.github.sarusm.meme.EmoteService;
import io.github.sarusm.meme.common.EmoteProto;

/**
 * The plugin-message transport: parses client packets ({@link EmoteProto} C2S opcodes) into
 * {@link EmoteService} calls, tracks which players run the Meme mod (= sent a HELLO), and drains the
 * per-player asset send queues at {@code chunks-per-tick} so a joining player's downloads never flood the
 * connection.
 */
public final class EmoteChannel implements PluginMessageListener, Listener {
    private final MemePlugin plugin;
    private final Set<UUID> modded = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Queue<byte[]>> assetQueues = new ConcurrentHashMap<>();
    /** Ceiling on queued bytes per player — a hostile client can't make the server buffer gigabytes. */
    private static final long MAX_QUEUED_BYTES = 64L * 1024 * 1024;

    public EmoteChannel(MemePlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, EmoteProto.CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, EmoteProto.CHANNEL, this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        int perTick = Math.max(1, plugin.getConfig().getInt("chunks-per-tick", 10));
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> drainQueues(perTick), 1L, 1L);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!EmoteProto.CHANNEL.equals(channel)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            byte op = in.readByte();
            switch (op) {
                case EmoteProto.C2S_HELLO -> {
                    int protocol = in.readInt();
                    String modVersion = in.readUTF();
                    modded.add(player.getUniqueId());
                    plugin.service().handleHello(player, protocol, modVersion);
                }
                case EmoteProto.C2S_REQUEST_ASSET -> plugin.service().handleRequestAsset(player, in.readUTF());
                case EmoteProto.C2S_PLAY -> plugin.service().handlePlay(player, in.readUTF());
                case EmoteProto.C2S_STOP -> plugin.service().handleStop(player);
                case EmoteProto.C2S_PURCHASE -> plugin.service().handlePurchase(player, in.readUTF());
                case EmoteProto.C2S_PLAY_LOCAL ->
                        plugin.service().handlePlayLocal(player, io.github.sarusm.meme.common.EmoteDef.read(in));
                case EmoteProto.C2S_SET_BUBBLE -> plugin.service().handleSetBubble(player, in.readUTF());
                default -> {
                    // newer client — ignore unknown opcodes
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Bad emote packet from " + player.getName() + ": " + e);
        }
    }

    /** Whether this player announced the client mod (only they receive emote traffic). */
    public boolean isModded(Player player) {
        return modded.contains(player.getUniqueId());
    }

    public void send(Player player, byte[] payload) {
        player.sendPluginMessage(plugin, EmoteProto.CHANNEL, payload);
    }

    /** Queue pre-chunked asset packets; dropped when the player is hammering the download API. */
    public void queueAsset(Player player, Iterable<byte[]> chunkPackets) {
        Queue<byte[]> queue = assetQueues.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
        long queued = 0;
        for (byte[] b : queue) {
            queued += b.length;
        }
        for (byte[] packet : chunkPackets) {
            if (queued > MAX_QUEUED_BYTES) {
                plugin.getLogger().warning("Asset queue overflow for " + player.getName() + "; dropping rest");
                return;
            }
            queue.add(packet);
            queued += packet.length;
        }
    }

    private void drainQueues(int perTick) {
        if (assetQueues.isEmpty()) {
            return;
        }
        assetQueues.entrySet().removeIf(entry -> {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                return true;
            }
            Queue<byte[]> queue = entry.getValue();
            for (int i = 0; i < perTick && !queue.isEmpty(); i++) {
                send(player, queue.poll());
            }
            return queue.isEmpty();
        });
    }

    /**
     * The Fabric mod announces its receivable channels via {@code minecraft:register} right after joining —
     * that's the earliest reliable "this player runs Meme" signal, so the catalogue is pushed here
     * proactively (the client's own C2S HELLO also triggers it, as a version-checked fallback/refresh).
     */
    @EventHandler
    public void onRegisterChannel(PlayerRegisterChannelEvent event) {
        if (!EmoteProto.CHANNEL.equals(event.getChannel())) {
            return;
        }
        modded.add(event.getPlayer().getUniqueId());
        plugin.service().sendHandshake(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        modded.remove(event.getPlayer().getUniqueId());
        assetQueues.remove(event.getPlayer().getUniqueId());
        plugin.service().handleQuit(event.getPlayer());
    }
}
