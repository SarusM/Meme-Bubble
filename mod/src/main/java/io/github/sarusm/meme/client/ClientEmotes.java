package io.github.sarusm.meme.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.sarusm.meme.common.EmoteDef;

/**
 * Client-side state for the connected server: the personalised emote catalogue (from {@code S2C_LIST} /
 * {@code S2C_ENTRY}), the emotes currently showing over players, and the in-flight asset downloads being
 * reassembled from chunks. Mutated only on the client main thread; cleared on disconnect (per-server data).
 */
public final class ClientEmotes {
    /**
     * One emote showing over a player. Wall-clock timed (GIF playback is time-based). Server emotes
     * resolve their def from the live catalogue (so {@code /memes set} tuning applies mid-play);
     * player-pack emotes carry the def inline — they exist in no catalogue.
     */
    public record Active(String emoteId, EmoteDef inlineDef, String bubbleOverride, long startMillis) {
        public EmoteDef resolveDef() {
            return inlineDef != null ? inlineDef : ClientEmotes.def(emoteId);
        }
    }

    /** One choosable speech bubble the server offers (its picker catalogue). */
    public record BubbleOption(String hash, String name, int size) {
    }

    /** A download being reassembled from {@code S2C_ASSET_CHUNK} packets. */
    public static final class Partial {
        public final byte[] data;
        public final boolean[] got;
        public int received;

        public Partial(int totalSize, int chunkCount) {
            this.data = new byte[totalSize];
            this.got = new boolean[chunkCount];
        }
    }

    private static final Map<String, EmoteDef> EMOTES = new LinkedHashMap<>();
    private static final Map<UUID, Active> ACTIVE = new HashMap<>();
    private static final Map<String, Partial> DOWNLOADS = new HashMap<>();
    private static boolean connected;
    /** This server allows emotes from the players' own local packs (S2C_PLAYER_EMOTES). */
    private static boolean playerEmotesAllowed;
    /** The server's choosable bubble catalogue (S2C_BUBBLES) and whether the picker is allowed at all. */
    private static final List<BubbleOption> BUBBLES = new ArrayList<>();
    private static boolean bubbleChoiceEnabled;
    /** Bumped on every catalogue change so an open panel knows to rebuild its widgets. Atomic because
     *  the local pack rescan (the only non-main-thread mutation in this class) bumps it off-thread. */
    private static final AtomicInteger REVISION = new AtomicInteger();

    private ClientEmotes() {
    }

    // --- Catalogue ---

    public static void setList(List<EmoteDef> defs) {
        EMOTES.clear();
        for (EmoteDef def : defs) {
            EMOTES.put(def.id, def);
        }
        connected = true;
        REVISION.incrementAndGet();
    }

    public static void updateEntry(EmoteDef def) {
        EMOTES.put(def.id, def);
        REVISION.incrementAndGet();
    }

    public static int revision() {
        return REVISION.get();
    }

    /** For catalogue-shaped changes outside this class (the local pack rescan). */
    public static void bumpRevision() {
        REVISION.incrementAndGet();
    }

    public static EmoteDef def(String id) {
        return EMOTES.get(id);
    }

    public static List<EmoteDef> all() {
        return List.copyOf(EMOTES.values());
    }

    /** The server def for the id, falling back to the player's own pack when the server allows it. */
    public static EmoteDef anyDef(String id) {
        EmoteDef def = EMOTES.get(id);
        if (def == null && playerEmotesAllowed) {
            def = LocalEmotes.get(id);
        }
        return def;
    }

    /**
     * The catalogue plus (when allowed) the player's own pack — skipping local emotes the server already
     * offers (same id or same primary-asset content), so the grid never shows duplicates. The primary
     * asset is the image, or the sound for sound-only emotes.
     */
    public static List<EmoteDef> allWithLocal() {
        List<EmoteDef> out = new ArrayList<>(EMOTES.values());
        if (playerEmotesAllowed) {
            Set<String> serverAssets = new HashSet<>();
            for (EmoteDef def : EMOTES.values()) {
                serverAssets.add(primaryHash(def));
            }
            for (EmoteDef local : LocalEmotes.all()) {
                if (!EMOTES.containsKey(local.id) && !serverAssets.contains(primaryHash(local))) {
                    out.add(local);
                }
            }
        }
        return out;
    }

    private static String primaryHash(EmoteDef def) {
        return def.gifHash.isEmpty() ? def.soundHash : def.gifHash;
    }

    /** True once the server answered our HELLO (i.e. it runs the Meme plugin). */
    public static boolean connected() {
        return connected;
    }

    public static boolean playerEmotesAllowed() {
        return playerEmotesAllowed;
    }

    public static void setPlayerEmotesAllowed(boolean allowed) {
        if (playerEmotesAllowed != allowed) {
            playerEmotesAllowed = allowed;
            REVISION.incrementAndGet();
        }
    }

    // --- Bubble catalogue (selectable speech bubbles) ---

    public static void setBubbles(boolean enabled, List<BubbleOption> options) {
        bubbleChoiceEnabled = enabled;
        BUBBLES.clear();
        BUBBLES.addAll(options);
        REVISION.incrementAndGet();
    }

    public static boolean bubbleChoiceEnabled() {
        return bubbleChoiceEnabled;
    }

    public static List<BubbleOption> bubbles() {
        return List.copyOf(BUBBLES);
    }

    // --- Active emotes ---

    /** {@code bubbleOverride}: the emitter's chosen bubble ("" = none, "none" = No bubble, or a hash). */
    public static void start(UUID player, String emoteId, String bubbleOverride) {
        ACTIVE.put(player, new Active(emoteId, null, bubbleOverride, System.currentTimeMillis()));
        EmoteDef def = EMOTES.get(emoteId);
        if (def != null && !def.soundHash.isEmpty()) {
            EmoteSounds.play(player, def);
        }
    }

    /**
     * A player-pack emote relayed by the server (S2C_PLAY_LOCAL). The server has no files for it: it
     * shows only if this viewer already has the emote's primary asset — the image, or for a sound-only
     * emote the sound — in their cache/pack (same SHA-1). Otherwise it is skipped entirely (never a
     * ghost entry waiting for a download that will never come).
     */
    public static void startLocal(UUID player, EmoteDef def) {
        boolean hasImage = AssetCache.has(def.gifHash);
        boolean hasSound = AssetCache.has(def.soundHash);
        if (def.gifHash.isEmpty() ? !hasSound : !hasImage) {
            return;
        }
        if (!hasSound) {
            def.soundHash = ""; // will never arrive — don't let the lifetime logic wait on it
        }
        ACTIVE.put(player, new Active(def.id, def, null, System.currentTimeMillis()));
        if (!def.soundHash.isEmpty()) {
            EmoteSounds.play(player, def);
        }
    }

    public static void stop(UUID player) {
        ACTIVE.remove(player);
        EmoteSounds.stop(player);
    }

    public static void stopAll() {
        ACTIVE.clear();
        EmoteSounds.stopAll();
    }

    public static Map<UUID, Active> active() {
        return ACTIVE;
    }

    /** Is this emote still the active one for the player? (read by the sound instance's tick) */
    public static boolean isActive(UUID player, String emoteId) {
        Active active = ACTIVE.get(player);
        return active != null && active.emoteId().equals(emoteId);
    }

    // --- Downloads ---

    public static Map<String, Partial> downloads() {
        return DOWNLOADS;
    }

    /** Everything is per-server: wipe on disconnect (the local pack itself is client-global and stays). */
    public static void clear() {
        EMOTES.clear();
        ACTIVE.clear();
        DOWNLOADS.clear();
        BUBBLES.clear();
        connected = false;
        playerEmotesAllowed = false;
        bubbleChoiceEnabled = false;
        REVISION.incrementAndGet();
        EmoteSounds.stopAll();
    }
}
