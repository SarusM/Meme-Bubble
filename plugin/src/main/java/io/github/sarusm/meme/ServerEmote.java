package io.github.sarusm.meme;

import java.io.File;

import io.github.sarusm.meme.common.EmoteDef;

/**
 * One emote as the SERVER knows it: the disk files behind the content hashes plus the owner's tuning from
 * emotes.yml. The per-player shop fields of {@link EmoteDef} are filled in by
 * {@link EmoteService#defFor} when the catalogue is sent.
 */
public final class ServerEmote {
    public final String id;
    public String name;

    /** Zip file name in emotes/packs/ this emote came from; "" for loose-folder emotes. */
    public String pack = "";

    public File gifFile;
    public String gifHash = "";
    public int gifSize;

    public File bubbleFile;
    public String bubbleHash = "";
    public int bubbleSize;

    public File soundFile;
    public String soundHash = "";
    public int soundSize;

    public double price;
    /** Everyone may use it without buying/granting. When price > 0 the yml default flips to false. */
    public boolean defaultAccess = true;

    public float scale = 1.2f;
    public float anchor = 1.15f;
    public float offsetX;
    public float offsetY = 0.25f;
    public float offsetZ;
    public float gifScale = 1.0f;
    public float gifOffsetX;
    public float gifOffsetY;
    public float soundVolume = 1.0f;
    public float soundPitch = 1.0f;
    public int soundRange = 32;
    public float loops = 1.0f;

    public ServerEmote(String id) {
        this.id = id;
        this.name = id;
    }

    /** The client-facing struct with the static (non-per-player) fields filled. */
    public EmoteDef baseDef() {
        EmoteDef d = new EmoteDef();
        d.id = id;
        d.name = name;
        d.gifHash = gifHash;
        d.gifSize = gifSize;
        d.bubbleHash = bubbleHash;
        d.bubbleSize = bubbleSize;
        d.soundHash = soundHash;
        d.soundSize = soundSize;
        d.price = price;
        d.scale = scale;
        d.anchor = anchor;
        d.offsetX = offsetX;
        d.offsetY = offsetY;
        d.offsetZ = offsetZ;
        d.gifScale = gifScale;
        d.gifOffsetX = gifOffsetX;
        d.gifOffsetY = gifOffsetY;
        d.soundVolume = soundVolume;
        d.soundPitch = soundPitch;
        d.soundRange = soundRange;
        d.loops = loops;
        return d;
    }
}
