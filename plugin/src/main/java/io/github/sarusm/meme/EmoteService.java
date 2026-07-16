package io.github.sarusm.meme;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;

import io.github.sarusm.meme.api.events.EmotePlayEvent;
import io.github.sarusm.meme.api.events.EmotePurchaseEvent;
import io.github.sarusm.meme.common.EmoteDef;
import io.github.sarusm.meme.common.EmoteProto;
import io.github.sarusm.meme.net.EmoteChannel;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Core server logic: personalises the catalogue per player, validates and broadcasts emote plays (access +
 * admin-mute + cooldown), answers asset downloads, and runs purchases through the economy provider. All
 * player-facing feedback is plain chat; catalogue updates go out as {@code S2C_ENTRY} so open client panels
 * refresh live.
 */
public final class EmoteService {
    private final MemePlugin plugin;
    private final Map<UUID, Long> lastPlay = new ConcurrentHashMap<>();
    /** Each modded player's chosen speech bubble ("none" = No bubble, or a catalogue hash); absent =
     *  default (no override). Client-authoritative: the client re-sends it (from ClientPrefs) on join. */
    private final Map<UUID, String> bubbleChoice = new ConcurrentHashMap<>();

    public EmoteService(MemePlugin plugin) {
        this.plugin = plugin;
    }

    private EmoteChannel channel() {
        return plugin.channel();
    }

    // ----------------------------------------------------------------------------------------------------
    // Handshake + catalogue
    // ----------------------------------------------------------------------------------------------------

    public void handleHello(Player player, int clientProtocol, String modVersion) {
        if (clientProtocol != EmoteProto.PROTOCOL_VERSION) {
            player.sendMessage(Component.text("[Meme] Версия мода Meme не совпадает с сервером ("
                    + modVersion + ") — обнови мод.", NamedTextColor.RED));
            return;
        }
        sendHandshake(player);
    }

    /**
     * S2C_HELLO + the full catalogue. Called on the client's C2S HELLO and proactively when the client
     * registers the plugin channel on join (so emotes work with no manual refresh).
     */
    public void sendHandshake(Player player) {
        channel().send(player, build(out -> {
            out.writeByte(EmoteProto.S2C_HELLO);
            out.writeInt(EmoteProto.PROTOCOL_VERSION);
        }));
        sendList(player);
    }

    public void sendList(Player player) {
        sendPlayerEmotesPolicy(player); // every list push also carries the player-pack policy
        sendBubbles(player);            // ...and the choosable bubble catalogue (or "disabled")
        List<EmoteDef> defs = new ArrayList<>();
        for (ServerEmote emote : plugin.registry().all()) {
            defs.add(defFor(player, emote));
        }
        channel().send(player, build(out -> {
            out.writeByte(EmoteProto.S2C_LIST);
            out.writeInt(defs.size());
            for (EmoteDef def : defs) {
                def.write(out);
            }
        }));
    }

    // ----------------------------------------------------------------------------------------------------
    // Player-pack emotes policy
    // ----------------------------------------------------------------------------------------------------

    /** May players use emotes from their OWN local packs on this server? */
    public boolean playerEmotesAllowed() {
        return plugin.getConfig().getBoolean("player-emotes", false);
    }

    /** Persist the toggle and push it live to every modded player (their panels show/hide the section). */
    public void setPlayerEmotesAllowed(boolean allowed) {
        plugin.getConfig().set("player-emotes", allowed);
        plugin.saveConfig();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (channel().isModded(p)) {
                sendPlayerEmotesPolicy(p);
            }
        }
    }

    public void sendPlayerEmotesPolicy(Player player) {
        boolean allowed = playerEmotesAllowed();
        channel().send(player, build(out -> {
            out.writeByte(EmoteProto.S2C_PLAYER_EMOTES);
            out.writeBoolean(allowed);
        }));
    }

    // ----------------------------------------------------------------------------------------------------
    // Selectable speech bubbles
    // ----------------------------------------------------------------------------------------------------

    /**
     * May players pick their own speech bubble? Its own {@code bubble-choice} switch, independent of
     * {@code player-emotes} — off forces the one fixed bubble each emote already carries.
     */
    public boolean bubbleChoiceEnabled() {
        return plugin.getConfig().getBoolean("bubble-choice", true);
    }

    /** Send the choosable bubble catalogue (empty + disabled when the server forbids the choice). */
    public void sendBubbles(Player player) {
        boolean enabled = bubbleChoiceEnabled();
        List<EmoteRegistry.BubbleOption> cat = enabled
                ? new ArrayList<>(plugin.registry().bubbleCatalogue()) : List.of();
        channel().send(player, build(out -> {
            out.writeByte(EmoteProto.S2C_BUBBLES);
            out.writeBoolean(enabled);
            out.writeInt(cat.size());
            for (EmoteRegistry.BubbleOption b : cat) {
                out.writeUTF(b.hash());
                out.writeUTF(b.name());
                out.writeInt(b.size());
            }
        }));
    }

    /** Record a player's bubble pick (validated against the catalogue); ignored when choice is off. */
    public void handleSetBubble(Player player, String choice) {
        if (!bubbleChoiceEnabled() || choice == null || choice.isEmpty()) {
            bubbleChoice.remove(player.getUniqueId()); // "" / disabled = default (no override)
            return;
        }
        if (choice.equals(EmoteProto.BUBBLE_NONE) || plugin.registry().isBubbleHash(choice)) {
            bubbleChoice.put(player.getUniqueId(), choice);
        }
        // else: a hash not in the catalogue (stale client) — leave the current choice untouched
    }

    /** The bubbleOverride to stamp on this player's plays ("" when choice is off or unset). */
    private String bubbleOverrideFor(Player player) {
        return bubbleChoiceEnabled()
                ? bubbleChoice.getOrDefault(player.getUniqueId(), "") : "";
    }

    public void sendEntry(Player player, ServerEmote emote) {
        EmoteDef def = defFor(player, emote);
        channel().send(player, build(out -> {
            out.writeByte(EmoteProto.S2C_ENTRY);
            def.write(out);
        }));
    }

    /** Push an updated entry to every online modded player (price/grant changes). */
    public void broadcastEntry(ServerEmote emote) {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (channel().isModded(p)) {
                sendEntry(p, emote);
            }
        }
    }

    /** The per-player personalised view of one emote. */
    public EmoteDef defFor(Player player, ServerEmote emote) {
        EmoteDef def = emote.baseDef();
        def.owned = plugin.data().isOwned(player.getUniqueId(), emote.id);
        def.canUse = canUse(player, emote);
        def.purchasable = !def.canUse && emote.price > 0;
        def.priceText = emote.price > 0 ? plugin.economy().format(emote.price) : "";
        return def;
    }

    public boolean canUse(Player player, ServerEmote emote) {
        return emote.defaultAccess
                || plugin.data().isOwned(player.getUniqueId(), emote.id)
                || plugin.data().isGranted(player.getUniqueId(), emote.id)
                || player.hasPermission("meme.emote." + emote.id);
    }

    // ----------------------------------------------------------------------------------------------------
    // Asset download
    // ----------------------------------------------------------------------------------------------------

    public void handleRequestAsset(Player player, String hash) {
        if (!hash.matches("[0-9a-f]{40}")) {
            return;
        }
        EmoteRegistry.Asset asset = plugin.registry().assetByHash(hash);
        if (asset == null) {
            return; // unknown hash — stale client cache or probing
        }
        byte[] data;
        try {
            data = asset.read();
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read emote asset " + asset.name() + ": " + e);
            return;
        }
        int chunkCount = Math.max(1, (data.length + EmoteProto.CHUNK_SIZE - 1) / EmoteProto.CHUNK_SIZE);
        List<byte[]> packets = new ArrayList<>(chunkCount);
        for (int i = 0; i < chunkCount; i++) {
            int from = i * EmoteProto.CHUNK_SIZE;
            int len = Math.min(EmoteProto.CHUNK_SIZE, data.length - from);
            int index = i;
            packets.add(build(out -> {
                out.writeByte(EmoteProto.S2C_ASSET_CHUNK);
                out.writeUTF(hash);
                out.writeInt(data.length);
                out.writeInt(index);
                out.writeInt(chunkCount);
                out.writeInt(len);
                out.write(data, from, len);
            }));
        }
        channel().queueAsset(player, packets);
    }

    // ----------------------------------------------------------------------------------------------------
    // Play / stop
    // ----------------------------------------------------------------------------------------------------

    /** Play an emote over {@code player}; false + chat feedback when rejected. */
    public boolean handlePlay(Player player, String emoteId) {
        ServerEmote emote = plugin.registry().get(emoteId);
        if (emote == null) {
            player.sendMessage(Component.text("[Meme] Нет такой эмоции: " + emoteId, NamedTextColor.RED));
            return false;
        }
        if (plugin.data().isMuted(player.getUniqueId())) {
            player.sendMessage(Component.text("[Meme] Твои эмоции отключены администратором.",
                    NamedTextColor.RED));
            return false;
        }
        if (!canUse(player, emote)) {
            player.sendMessage(Component.text("[Meme] Эмоция недоступна."
                    + (emote.price > 0 ? " Цена: " + plugin.economy().format(emote.price) : ""),
                    NamedTextColor.RED));
            return false;
        }
        long cooldownMs = plugin.getConfig().getInt("cooldown-seconds", 2) * 1000L;
        long now = System.currentTimeMillis();
        Long last = lastPlay.get(player.getUniqueId());
        if (last != null && now - last < cooldownMs) {
            return false; // silent — spam clicking the panel shouldn't spam chat
        }
        EmotePlayEvent event = new EmotePlayEvent(player, emote.id);
        plugin.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return false;
        }
        lastPlay.put(player.getUniqueId(), now);
        String override = bubbleOverrideFor(player);
        broadcast(player, build(out -> {
            out.writeByte(EmoteProto.S2C_PLAY);
            out.writeUTF(player.getUniqueId().toString());
            out.writeInt(player.getEntityId());
            out.writeUTF(emote.id);
            out.writeUTF(override);
        }));
        return true;
    }

    /**
     * Play an emote from the player's OWN local pack: no server files involved — the sanitised def (with
     * its content hashes) is just relayed to everyone in range, and each viewer shows it only if they
     * already have the same files (same SHA-1) locally. Server emotes are completely unaffected.
     */
    public boolean handlePlayLocal(Player player, EmoteDef def) {
        if (!playerEmotesAllowed()) {
            player.sendMessage(Component.text("[Meme] Свои эмоции отключены на этом сервере.",
                    NamedTextColor.RED));
            return false;
        }
        if (plugin.data().isMuted(player.getUniqueId())) {
            player.sendMessage(Component.text("[Meme] Твои эмоции отключены администратором.",
                    NamedTextColor.RED));
            return false;
        }
        if (!sanitizePlayerDef(def)) {
            return false; // malformed packet (not producible by the stock mod) — drop silently
        }
        long cooldownMs = plugin.getConfig().getInt("cooldown-seconds", 2) * 1000L;
        long now = System.currentTimeMillis();
        Long last = lastPlay.get(player.getUniqueId());
        if (last != null && now - last < cooldownMs) {
            return false; // same silent anti-spam as server emotes
        }
        EmotePlayEvent event = new EmotePlayEvent(player, def.id);
        plugin.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return false;
        }
        lastPlay.put(player.getUniqueId(), now);
        // The emitter's chosen bubble overrides the pack's own before the def is relayed.
        String override = bubbleOverrideFor(player);
        if (!override.isEmpty()) {
            def.bubbleHash = override.equals(EmoteProto.BUBBLE_NONE) ? "" : override;
            def.bubbleSize = 0; // advisory only; the viewer fetches the bubble by hash
        }
        broadcast(player, build(out -> {
            out.writeByte(EmoteProto.S2C_PLAY_LOCAL);
            out.writeUTF(player.getUniqueId().toString());
            def.write(out);
        }));
        return true;
    }

    /**
     * Validate and clamp a client-supplied def before relaying it (the client is untrusted: no absurd
     * scales/ranges, no bogus hashes, no shop state). False = reject the packet entirely.
     */
    private static boolean sanitizePlayerDef(EmoteDef def) {
        if (def.id == null || def.name == null
                || def.gifHash == null || !(def.gifHash.isEmpty() || def.gifHash.matches("[0-9a-f]{40}"))
                || def.bubbleHash == null || !(def.bubbleHash.isEmpty() || def.bubbleHash.matches("[0-9a-f]{40}"))
                || def.soundHash == null || !(def.soundHash.isEmpty() || def.soundHash.matches("[0-9a-f]{40}"))) {
            return false;
        }
        if (def.gifHash.isEmpty() && def.soundHash.isEmpty()) {
            return false; // an emote is at least an image or a sound
        }
        def.id = def.id.strip();
        if (def.id.isEmpty() || def.id.length() > 48) {
            return false;
        }
        def.name = def.name.isBlank() ? def.id : def.name.strip();
        if (def.name.length() > 64) {
            def.name = def.name.substring(0, 64);
        }
        // Shop state is meaningless for a relayed player emote — zero it so viewers can't be confused.
        def.price = 0;
        def.priceText = "";
        def.canUse = true;
        def.owned = false;
        def.purchasable = false;
        def.gifSize = clampInt(def.gifSize, 0, 64 * 1024 * 1024);
        def.bubbleSize = clampInt(def.bubbleSize, 0, 64 * 1024 * 1024);
        def.soundSize = clampInt(def.soundSize, 0, 64 * 1024 * 1024);
        def.scale = clampFloat(def.scale, 0.2f, 5.0f, 1.2f);
        def.anchor = clampFloat(def.anchor, 0.0f, 3.0f, 1.15f);
        def.offsetX = clampFloat(def.offsetX, -10.0f, 10.0f, 0.0f);
        def.offsetY = clampFloat(def.offsetY, -10.0f, 10.0f, 0.25f);
        def.offsetZ = clampFloat(def.offsetZ, -10.0f, 10.0f, 0.0f);
        def.gifScale = clampFloat(def.gifScale, 0.05f, 5.0f, 1.0f);
        def.gifOffsetX = clampFloat(def.gifOffsetX, -2048.0f, 2048.0f, 0.0f);
        def.gifOffsetY = clampFloat(def.gifOffsetY, -2048.0f, 2048.0f, 0.0f);
        def.soundVolume = clampFloat(def.soundVolume, 0.0f, 2.0f, 1.0f);
        def.soundPitch = clampFloat(def.soundPitch, 0.5f, 2.0f, 1.0f);
        def.soundRange = clampInt(def.soundRange, 1, 128);
        def.loops = clampFloat(def.loops, 0.0f, 1000.0f, 1.0f);
        return true;
    }

    private static int clampInt(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }

    /** NaN-safe clamp (Math.min/max would propagate a crafted NaN straight into every viewer's renderer). */
    private static float clampFloat(float value, float min, float max, float fallback) {
        if (!Float.isFinite(value)) {
            return fallback;
        }
        return Math.min(max, Math.max(min, value));
    }

    public void handleStop(Player player) {
        broadcast(player, build(out -> {
            out.writeByte(EmoteProto.S2C_STOP);
            out.writeUTF(player.getUniqueId().toString());
        }));
    }

    public void handleQuit(Player player) {
        lastPlay.remove(player.getUniqueId());
        bubbleChoice.remove(player.getUniqueId());
        // Their bubble dies with them: tell everyone still online.
        broadcast(player, build(out -> {
            out.writeByte(EmoteProto.S2C_STOP);
            out.writeUTF(player.getUniqueId().toString());
        }));
    }

    public void stopAll() {
        byte[] packet = build(out -> out.writeByte(EmoteProto.S2C_STOP_ALL));
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (channel().isModded(p)) {
                channel().send(p, packet);
            }
        }
    }

    /** Send to every modded player near the emitter (same world, visible-range), always incl. the emitter. */
    private void broadcast(Player emitter, byte[] packet) {
        double range = plugin.getConfig().getDouble("visible-range", 96.0);
        double rangeSq = range * range;
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (!channel().isModded(p)) {
                continue;
            }
            if (p == emitter
                    || (p.getWorld() == emitter.getWorld()
                        && p.getLocation().distanceSquared(emitter.getLocation()) <= rangeSq)) {
                channel().send(p, packet);
            }
        }
    }

    // ----------------------------------------------------------------------------------------------------
    // Purchases
    // ----------------------------------------------------------------------------------------------------

    public void handlePurchase(Player player, String emoteId) {
        ServerEmote emote = plugin.registry().get(emoteId);
        if (emote == null || emote.price <= 0) {
            return;
        }
        if (canUse(player, emote)) {
            sendEntry(player, emote); // already usable — just refresh their panel
            return;
        }
        String priceText = plugin.economy().format(emote.price);
        EmotePurchaseEvent event = new EmotePurchaseEvent(player, emote.id, emote.price);
        plugin.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }
        if (!plugin.economy().withdraw(player, emote.price)) {
            player.sendMessage(Component.text("[Meme] Недостаточно средств: нужно " + priceText + ".",
                    NamedTextColor.RED));
            return;
        }
        plugin.data().addOwned(player.getUniqueId(), emote.id);
        player.sendMessage(Component.text("[Meme] Куплена эмоция \"" + emote.name + "\" за "
                + priceText + "!", NamedTextColor.GREEN));
        sendEntry(player, emote);
    }

    // ----------------------------------------------------------------------------------------------------
    // Payload building
    // ----------------------------------------------------------------------------------------------------

    @FunctionalInterface
    public interface PayloadWriter {
        void write(DataOutputStream out) throws IOException;
    }

    public static byte[] build(PayloadWriter writer) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writer.write(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // ByteArrayOutputStream can't actually throw
        }
        return bytes.toByteArray();
    }
}
