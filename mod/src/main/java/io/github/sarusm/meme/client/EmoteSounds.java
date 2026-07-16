package io.github.sarusm.meme.client;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.github.sarusm.meme.common.EmoteDef;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.entity.player.Player;

/**
 * Plays a server emote's downloaded OGG at the emitting player, through Minecraft's own sound engine.
 *
 * <p>The trick: the {@link SoundInstance} resolves itself to a synthetic {@link Sound} under the
 * {@code meme:} namespace (bypassing sounds.json), and {@code SoundBufferLibraryMixin} intercepts the
 * engine's resource lookup for that namespace and serves the cached file from disk ({@link #resolveVirtual}).
 * That way we get positional audio, distance attenuation, the sound options screen and device handling for
 * free — no resource-pack reload, no raw OpenAL.
 *
 * <p>The instance is tickable: it follows the player, applies the viewer's live volume preferences
 * ({@link ClientPrefs}), and stops itself the moment the emote ends or the emitter gets muted.
 */
public final class EmoteSounds {
    private static final Map<UUID, EmoteSoundInstance> PLAYING = new HashMap<>();
    /** OGG duration memo by content hash; 0 = file present but unparseable (caller falls back). */
    private static final Map<String, Long> DURATIONS = new HashMap<>();

    private EmoteSounds() {
    }

    /**
     * The cached OGG's play length in ms — the lifetime unit of emotes without an image ({@code loops ×}
     * this). -1 while the file isn't downloaded yet; 0 when it's present but the header can't be parsed.
     */
    public static long durationMs(String hash) {
        if (!AssetCache.has(hash)) {
            return -1;
        }
        return DURATIONS.computeIfAbsent(hash, h -> parseOggDurationMs(AssetCache.path(h)));
    }

    /**
     * Vorbis duration straight from the container: sample rate from the identification header
     * ({@code 0x01 "vorbis"} packet), total samples = the granule position of the last OGG page.
     */
    private static long parseOggDurationMs(Path file) {
        try {
            byte[] data = java.nio.file.Files.readAllBytes(file);
            int rate = 0;
            for (int i = 0; i + 16 <= data.length; i++) {
                if (data[i] == 0x01 && data[i + 1] == 'v' && data[i + 2] == 'o' && data[i + 3] == 'r'
                        && data[i + 4] == 'b' && data[i + 5] == 'i' && data[i + 6] == 's') {
                    // 1 type + 6 "vorbis" + 4 version + 1 channels, then the LE sample rate.
                    rate = (data[i + 12] & 0xFF) | (data[i + 13] & 0xFF) << 8
                            | (data[i + 14] & 0xFF) << 16 | (data[i + 15] & 0xFF) << 24;
                    break;
                }
            }
            if (rate <= 0) {
                return 0;
            }
            for (int i = data.length - 14; i >= 0; i--) {
                if (data[i] == 'O' && data[i + 1] == 'g' && data[i + 2] == 'g' && data[i + 3] == 'S') {
                    long granule = 0;
                    for (int b = 7; b >= 0; b--) {
                        granule = granule << 8 | (data[i + 6 + b] & 0xFF);
                    }
                    return granule <= 0 ? 0 : granule * 1000L / rate;
                }
            }
            return 0;
        } catch (java.io.IOException e) {
            MemeClient.LOGGER.warn("Could not read emote sound {} for its duration", file, e);
            return 0;
        }
    }

    /** Start the emote's sound over the player (silently skipped while the file is still downloading). */
    public static void play(UUID playerId, EmoteDef def) {
        stop(playerId);
        if (!AssetCache.has(def.soundHash) || ClientPrefs.isHidden(playerId)) {
            return;
        }
        AbstractClientPlayer player = findPlayer(playerId);
        if (player == null) {
            return; // not visible to us — no positional anchor, no sound
        }
        EmoteSoundInstance instance = new EmoteSoundInstance(playerId, def, player);
        PLAYING.put(playerId, instance);
        Minecraft.getInstance().getSoundManager().play(instance);
    }

    public static void stop(UUID playerId) {
        EmoteSoundInstance instance = PLAYING.remove(playerId);
        if (instance != null) {
            Minecraft.getInstance().getSoundManager().stop(instance);
        }
    }

    public static void stopAll() {
        for (EmoteSoundInstance instance : PLAYING.values()) {
            Minecraft.getInstance().getSoundManager().stop(instance);
        }
        PLAYING.clear();
    }

    static AbstractClientPlayer findPlayer(UUID id) {
        if (Minecraft.getInstance().level == null) {
            return null;
        }
        for (AbstractClientPlayer p : Minecraft.getInstance().level.players()) {
            if (p.getUUID().equals(id)) {
                return p;
            }
        }
        return null;
    }

    /**
     * The disk file behind a {@code meme:sounds/<sha1>.ogg} location the sound engine asks for
     * (called from the {@code SoundBufferLibrary} mixin); null when it's not ours / not cached.
     */
    public static Path resolveVirtual(Identifier location) {
        if (!"meme".equals(location.getNamespace())) {
            return null;
        }
        String path = location.getPath();
        if (!path.startsWith("sounds/") || !path.endsWith(".ogg")) {
            return null;
        }
        String hash = path.substring("sounds/".length(), path.length() - ".ogg".length());
        return AssetCache.has(hash) ? AssetCache.path(hash) : null;
    }

    /**
     * One playing emote sound: follows the emitter, live viewer volume, self-stopping.
     * Extends {@link AbstractSoundInstance} directly (its {@code Identifier} constructor bypasses the
     * SoundEvent registry) and implements the tickable interface by hand — vanilla's
     * AbstractTickableSoundInstance only offers the SoundEvent constructor.
     */
    private static final class EmoteSoundInstance extends AbstractSoundInstance
            implements TickableSoundInstance {
        private final UUID playerId;
        private final EmoteDef def;
        private boolean stopped;

        EmoteSoundInstance(UUID playerId, EmoteDef def, Player player) {
            super(Identifier.fromNamespaceAndPath("meme", def.soundHash),
                    SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
            this.playerId = playerId;
            this.def = def;
            this.looping = false;
            this.attenuation = Attenuation.LINEAR;
            this.volume = def.soundVolume * ClientPrefs.effectiveVolume(playerId);
            this.pitch = def.soundPitch;
            this.x = player.getX();
            this.y = player.getY();
            this.z = player.getZ();
        }

        @Override
        public WeighedSoundEvents resolve(SoundManager manager) {
            // Bypass sounds.json: hand the engine a synthetic streamed Sound whose file lookup the
            // SoundBufferLibrary mixin redirects into the asset cache.
            this.sound = new Sound(this.identifier, ConstantFloat.of(1.0f), ConstantFloat.of(1.0f), 1,
                    Sound.Type.FILE, true /* stream -> getStream() path */, false, def.soundRange);
            return new WeighedSoundEvents(this.identifier, null);
        }

        @Override
        public boolean isStopped() {
            return stopped;
        }

        @Override
        public void tick() {
            if (!ClientEmotes.isActive(playerId, def.id) || ClientPrefs.isHidden(playerId)) {
                stopped = true;
                PLAYING.remove(playerId, this);
                return;
            }
            AbstractClientPlayer player = findPlayer(playerId);
            if (player != null) {
                this.x = player.getX();
                this.y = player.getY();
                this.z = player.getZ();
            }
            // The engine re-reads volume every tick for tickable instances -> live slider response.
            this.volume = def.soundVolume * ClientPrefs.effectiveVolume(playerId);
        }
    }
}
