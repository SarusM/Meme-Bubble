package io.github.sarusm.meme.client.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.github.sarusm.meme.client.AssetCache;
import io.github.sarusm.meme.client.MemeClient;
import io.github.sarusm.meme.client.ClientEmotes;
import io.github.sarusm.meme.client.ClientPrefs;
import io.github.sarusm.meme.client.LocalEmotes;
import io.github.sarusm.meme.common.EmoteDef;
import io.github.sarusm.meme.common.EmoteProto;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Encodes/decodes the {@link EmoteProto} byte protocol on the client. Receive path runs on the client main
 * thread (the initializer hops via {@code context.client().execute}); asset chunks are reassembled in
 * {@link ClientEmotes#downloads()} and verified+persisted by {@link AssetCache} when complete.
 */
public final class ClientNet {
    /** Hashes already asked for this session — a missing file is only requested once. */
    private static final Set<String> REQUESTED = new HashSet<>();

    private ClientNet() {
    }

    // ----------------------------------------------------------------------------------------------------
    // Receive
    // ----------------------------------------------------------------------------------------------------

    public static void handle(byte[] message) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            byte op = in.readByte();
            switch (op) {
                case EmoteProto.S2C_HELLO -> {
                    int protocol = in.readInt();
                    if (protocol != EmoteProto.PROTOCOL_VERSION) {
                        MemeClient.LOGGER.warn(
                                "Server emote protocol {} != client {}", protocol, EmoteProto.PROTOCOL_VERSION);
                    }
                }
                case EmoteProto.S2C_LIST -> {
                    int count = in.readInt();
                    List<EmoteDef> defs = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        defs.add(EmoteDef.read(in));
                    }
                    ClientEmotes.setList(defs);
                    requestMissingAssets(defs);
                    MemeClient.LOGGER.info("Received {} server emotes", count);
                }
                case EmoteProto.S2C_ENTRY -> {
                    EmoteDef def = EmoteDef.read(in);
                    ClientEmotes.updateEntry(def);
                    requestMissingAssets(List.of(def));
                }
                case EmoteProto.S2C_ASSET_CHUNK -> handleChunk(in);
                case EmoteProto.S2C_PLAY -> {
                    UUID player = UUID.fromString(in.readUTF());
                    in.readInt(); // entityId hint — uuid is authoritative
                    String emoteId = in.readUTF();
                    String bubbleOverride = in.readUTF(); // the emitter's chosen bubble ("" / "none" / hash)
                    ClientEmotes.start(player, emoteId, bubbleOverride);
                    requestBubble(bubbleOverride);
                }
                case EmoteProto.S2C_STOP -> ClientEmotes.stop(UUID.fromString(in.readUTF()));
                case EmoteProto.S2C_STOP_ALL -> ClientEmotes.stopAll();
                case EmoteProto.S2C_PLAYER_EMOTES -> ClientEmotes.setPlayerEmotesAllowed(in.readBoolean());
                case EmoteProto.S2C_BUBBLES -> handleBubbles(in);
                case EmoteProto.S2C_PLAY_LOCAL -> {
                    UUID player = UUID.fromString(in.readUTF());
                    EmoteDef def = EmoteDef.read(in);
                    ClientEmotes.startLocal(player, def);
                    // Its own hashes come from the player's pack; only a server-side bubble OVERRIDE (a
                    // catalogue bubble the server can serve) needs fetching — harmless if it can't.
                    requestBubble(def.bubbleHash);
                }
                default -> {
                    // newer server — ignore unknown opcodes
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            MemeClient.LOGGER.warn("Bad emote packet from server", e);
        }
    }

    private static void handleChunk(DataInputStream in) throws IOException {
        String hash = in.readUTF();
        int totalSize = in.readInt();
        int chunkIndex = in.readInt();
        int chunkCount = in.readInt();
        int len = in.readInt();
        if (totalSize < 0 || totalSize > 64 * 1024 * 1024 || chunkCount <= 0
                || chunkIndex < 0 || chunkIndex >= chunkCount || len < 0) {
            return;
        }
        ClientEmotes.Partial partial = ClientEmotes.downloads()
                .computeIfAbsent(hash, h -> new ClientEmotes.Partial(totalSize, chunkCount));
        int offset = chunkIndex * EmoteProto.CHUNK_SIZE;
        if (partial.data.length != totalSize || offset + len > totalSize) {
            return;
        }
        in.readFully(partial.data, offset, len);
        if (!partial.got[chunkIndex]) {
            partial.got[chunkIndex] = true;
            partial.received++;
        }
        if (partial.received == partial.got.length) {
            ClientEmotes.downloads().remove(hash);
            if (AssetCache.put(hash, partial.data)) {
                MemeClient.LOGGER.info("Downloaded emote asset {} ({} KB)", hash, totalSize / 1024);
            } else {
                REQUESTED.remove(hash); // corrupt transfer — allow a retry
            }
        }
    }

    private static void requestMissingAssets(List<EmoteDef> defs) {
        for (EmoteDef def : defs) {
            for (String hash : new String[] {def.gifHash, def.bubbleHash, def.soundHash}) {
                if (!hash.isEmpty() && !AssetCache.has(hash) && REQUESTED.add(hash)) {
                    requestAsset(hash);
                }
            }
        }
    }

    /** Fetch a bubble by hash if it's a real (downloadable) one we don't have; skips "" and "none". */
    private static void requestBubble(String hash) {
        if (!hash.isEmpty() && !hash.equals(EmoteProto.BUBBLE_NONE)
                && !AssetCache.has(hash) && REQUESTED.add(hash)) {
            requestAsset(hash);
        }
    }

    private static void handleBubbles(DataInputStream in) throws IOException {
        boolean enabled = in.readBoolean();
        int count = in.readInt();
        List<ClientEmotes.BubbleOption> options = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            String hash = in.readUTF();
            String name = in.readUTF();
            int size = in.readInt();
            options.add(new ClientEmotes.BubbleOption(hash, name, size));
            requestBubble(hash); // prefetch so the picker can preview + the override renders instantly
        }
        ClientEmotes.setBubbles(enabled, options);
        MemeClient.LOGGER.info("Bubble picker {}: {} bubble(s) offered",
                enabled ? "enabled" : "disabled", options.size());
        if (enabled) {
            setBubble(ClientPrefs.bubble()); // re-assert our saved pick on this (validating) server
        }
    }

    // ----------------------------------------------------------------------------------------------------
    // Send
    // ----------------------------------------------------------------------------------------------------

    public static void hello() {
        send(out -> {
            out.writeByte(EmoteProto.C2S_HELLO);
            out.writeInt(EmoteProto.PROTOCOL_VERSION);
            out.writeUTF("1.0.0");
        });
    }

    public static void requestAsset(String hash) {
        send(out -> {
            out.writeByte(EmoteProto.C2S_REQUEST_ASSET);
            out.writeUTF(hash);
        });
    }

    public static void play(String emoteId) {
        send(out -> {
            out.writeByte(EmoteProto.C2S_PLAY);
            out.writeUTF(emoteId);
        });
    }

    /** Tell the server our chosen speech bubble ("" = default, "none" = No bubble, or a catalogue hash). */
    public static void setBubble(String choice) {
        send(out -> {
            out.writeByte(EmoteProto.C2S_SET_BUBBLE);
            out.writeUTF(choice == null ? "" : choice);
        });
    }

    /** Play an emote from the player's own local pack: only the def + hashes go to the server. */
    public static void playLocal(EmoteDef def) {
        send(out -> {
            out.writeByte(EmoteProto.C2S_PLAY_LOCAL);
            def.write(out);
        });
    }

    /**
     * Play by id from wherever it lives: the server catalogue wins, otherwise the player's own pack
     * (when this server allows it). Unknown ids are ignored — used by hotkeys and the wheel, whose
     * bindings are global across servers.
     */
    public static void playAny(String emoteId) {
        if (ClientEmotes.def(emoteId) != null) {
            play(emoteId);
            return;
        }
        EmoteDef local = ClientEmotes.playerEmotesAllowed() ? LocalEmotes.get(emoteId) : null;
        if (local != null) {
            playLocal(local);
        }
    }

    public static void stopSelf() {
        send(out -> out.writeByte(EmoteProto.C2S_STOP));
    }

    public static void purchase(String emoteId) {
        send(out -> {
            out.writeByte(EmoteProto.C2S_PURCHASE);
            out.writeUTF(emoteId);
        });
    }

    /** New-session reset (the REQUESTED set is per-connection: a new server may host new files). */
    public static void reset() {
        REQUESTED.clear();
    }

    @FunctionalInterface
    private interface PayloadWriter {
        void write(DataOutputStream out) throws IOException;
    }

    private static void send(PayloadWriter writer) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(32);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writer.write(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // ByteArrayOutputStream can't actually throw
        }
        ClientPlayNetworking.send(new EmotesPayload(bytes.toByteArray()));
    }
}
