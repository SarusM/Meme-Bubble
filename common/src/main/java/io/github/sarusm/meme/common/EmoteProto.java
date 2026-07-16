package io.github.sarusm.meme.common;

/**
 * The wire protocol between the Meme Paper plugin (server) and the Meme Fabric mod (client),
 * carried over the plugin-message channel {@link #CHANNEL}. Both sides compile this exact file (the
 * {@code common/} source folder is added to both Gradle projects), so the encodings can never drift.
 *
 * <p>Every message is: {@code [byte opcode][opcode-specific body]}, written with
 * {@code java.io.DataOutputStream} (UTF strings = {@code writeUTF}).
 *
 * <p><b>Server → client</b> (clientbound payload limit is 1 MiB; assets are chunked far below that):
 * <ul>
 *   <li>{@link #S2C_HELLO}: {@code int protocolVersion} — handshake reply; the list follows.</li>
 *   <li>{@link #S2C_LIST}: {@code int count} then {@code count ×} {@link EmoteDef} — the full, per-player
 *       personalised emote catalogue (access/ownership/price baked in).</li>
 *   <li>{@link #S2C_ENTRY}: one {@link EmoteDef} — replaces that id in the client list (after a purchase,
 *       grant or price change).</li>
 *   <li>{@link #S2C_ASSET_CHUNK}: {@code UTF sha1, int totalSize, int chunkIndex, int chunkCount,
 *       int byteCount, byte[byteCount]} — one slice of a requested asset file.</li>
 *   <li>{@link #S2C_PLAY}: {@code UTF playerUuid, int entityId, UTF emoteId, UTF bubbleOverride} — show
 *       the emote over that player (uuid is authoritative; entityId is a hint). {@code bubbleOverride} is
 *       the emitter's chosen speech-bubble applied over the emote's own: {@code ""} = none (use the
 *       emote's bubble), {@code "none"} = the "No bubble" choice (draw no bubble), a 40-hex SHA-1 = that
 *       bubble from the catalogue.</li>
 *   <li>{@link #S2C_STOP}: {@code UTF playerUuid} — clear that player's emote.</li>
 *   <li>{@link #S2C_STOP_ALL}: no body.</li>
 *   <li>{@link #S2C_PLAYER_EMOTES}: {@code boolean allowed} — whether this server permits emotes from
 *       the players' OWN local packs; sent with every list push and on live toggle.</li>
 *   <li>{@link #S2C_PLAY_LOCAL}: {@code UTF playerUuid} then one {@link EmoteDef} — show a PLAYER-pack
 *       emote over that player. The server has no files for it: viewers render it only if the referenced
 *       hashes are already in their local cache/pack (same file = same SHA-1 = visible). The emitter's
 *       chosen bubble is already baked into {@code def.bubbleHash} by the server.</li>
 *   <li>{@link #S2C_BUBBLES}: {@code boolean enabled, int count} then {@code count ×
 *       {UTF hash, UTF name, int size}} — the choosable speech-bubble catalogue (every bubble PNG the
 *       server offers). {@code enabled=false} (count 0) means the server forbids bubble choice — clients
 *       hide the picker and one fixed bubble is always used. Sent with every list push and on toggle.</li>
 * </ul>
 *
 * <p><b>Client → server</b> (serverbound payload limit is 32 KiB; all of these are tiny):
 * <ul>
 *   <li>{@link #C2S_HELLO}: {@code int protocolVersion, UTF modVersion} — sent on join; marks the player
 *       as modded and triggers {@code S2C_HELLO + S2C_LIST}.</li>
 *   <li>{@link #C2S_REQUEST_ASSET}: {@code UTF sha1} — ask for a file listed in the catalogue.</li>
 *   <li>{@link #C2S_PLAY}: {@code UTF emoteId} — play an emote on yourself (server validates access,
 *       admin-mute and cooldown).</li>
 *   <li>{@link #C2S_STOP}: no body — stop your own emote.</li>
 *   <li>{@link #C2S_PURCHASE}: {@code UTF emoteId} — buy the emote through the server's economy.</li>
 *   <li>{@link #C2S_PLAY_LOCAL}: one {@link EmoteDef} — play an emote from the sender's OWN local pack
 *       (only the def travels, never the files; the server sanitises it and relays as
 *       {@code S2C_PLAY_LOCAL} when {@code player-emotes} is enabled).</li>
 *   <li>{@link #C2S_SET_BUBBLE}: {@code UTF choice} — the player's chosen speech-bubble for their emotes:
 *       {@code ""} = default (no override), {@code "none"} = No bubble, a 40-hex SHA-1 = that catalogue
 *       bubble. The server validates it against the catalogue and applies it to the player's plays.</li>
 * </ul>
 */
public final class EmoteProto {
    /** The plugin-message channel both sides register. */
    public static final String CHANNEL = "meme:main";
    /**
     * Bump when the wire format changes; a mismatch is reported to the player in chat.
     * v3: {@code EmoteDef.gifHash} may be empty (sound-only emotes) and may reference a static
     * PNG/JPG instead of a GIF — same byte layout, new semantics old clients mishandle.
     * v4: player-selectable speech bubbles — {@code S2C_BUBBLES} catalogue, {@code C2S_SET_BUBBLE},
     * and a trailing {@code UTF bubbleOverride} on {@code S2C_PLAY}.
     */
    public static final int PROTOCOL_VERSION = 4;

    /** Asset slice size. Stays far under both the 1 MiB clientbound and Bukkit messenger limits. */
    public static final int CHUNK_SIZE = 28_000;

    // Server -> client
    public static final byte S2C_HELLO = 1;
    public static final byte S2C_LIST = 2;
    public static final byte S2C_ENTRY = 3;
    public static final byte S2C_ASSET_CHUNK = 4;
    public static final byte S2C_PLAY = 5;
    public static final byte S2C_STOP = 6;
    public static final byte S2C_STOP_ALL = 7;
    public static final byte S2C_PLAYER_EMOTES = 8;
    public static final byte S2C_PLAY_LOCAL = 9;
    public static final byte S2C_BUBBLES = 10;

    // Client -> server
    public static final byte C2S_HELLO = 1;
    public static final byte C2S_REQUEST_ASSET = 2;
    public static final byte C2S_PLAY = 3;
    public static final byte C2S_STOP = 4;
    public static final byte C2S_PURCHASE = 5;
    public static final byte C2S_PLAY_LOCAL = 6;
    public static final byte C2S_SET_BUBBLE = 7;

    /** The {@link #C2S_SET_BUBBLE} / bubbleOverride sentinel meaning "draw no bubble at all". */
    public static final String BUBBLE_NONE = "none";

    private EmoteProto() {
    }
}
