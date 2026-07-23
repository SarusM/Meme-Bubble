package io.github.sarusm.meme.common;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * One emote as the CLIENT sees it: identity, the content-addressed asset references (SHA-1 hex + size —
 * the client downloads whatever its cache is missing via {@link EmoteProto#C2S_REQUEST_ASSET}), the
 * player-personalised shop state (access / ownership / price), and the render/sound tuning the server
 * owner configured in {@code emotes.yml}. Serialised inside {@link EmoteProto#S2C_LIST} /
 * {@link EmoteProto#S2C_ENTRY}. Plain mutable struct — shared verbatim by the plugin and the mod.
 */
public final class EmoteDef {
    public String id = "";
    /** Display name (may contain spaces/unicode; the id is the command-safe key). */
    public String name = "";

    /**
     * SHA-1 hex of the image file (animated GIF or static PNG/JPG); empty = sound-only emote
     * (the client then shows {@link #name} as text in the bubble / on a white plate).
     */
    public String gifHash = "";
    public int gifSize;
    /** SHA-1 hex of the speech-bubble PNG; empty = no bubble (gif floats alone). */
    public String bubbleHash = "";
    public int bubbleSize;
    /** SHA-1 hex of the OGG-Vorbis sound; empty = silent emote. */
    public String soundHash = "";
    public int soundSize;

    // --- Shop state, personalised for the receiving player ---
    /** Price in the server's currency; 0 = free. */
    public double price;
    /** Pre-formatted price ("150 Coins") so the client never needs to know the currency. */
    public String priceText = "";
    /** The player may play this emote right now (default access, purchased, granted or permission). */
    public boolean canUse;
    /** The player has purchased it. */
    public boolean owned;
    /** Not usable yet but buyable ({@code price > 0}). */
    public boolean purchasable;

    // --- Bubble placement over the player (server-owner tuning) ---
    /** Bubble height in blocks. */
    public float scale = 1.2f;
    /** Bubble bottom sits at {@code entityHeight × anchor} above the player's feet. */
    public float anchor = 1.15f;
    /** Extra offsets in the billboard frame: right / up / toward the camera, in blocks. */
    public float offsetX;
    public float offsetY = 0.25f;
    public float offsetZ;

    // --- GIF seat inside the bubble ---
    /** Multiplier on the largest aspect-preserving ("contain") fit. */
    public float gifScale = 1.0f;
    /** Offsets of the gif's bottom-center from the bubble's bottom-center, in bubble-texture pixels. */
    public float gifOffsetX;
    public float gifOffsetY;

    // --- Sound tuning ---
    public float soundVolume = 1.0f;
    public float soundPitch = 1.0f;
    /** Audible range in blocks (linear attenuation). */
    public int soundRange = 32;

    /**
     * The emote lasts this many cycles — a GIF animation cycle; for static/sound-only emotes the
     * sound's play length (client-parsed), else a fixed 3 s. 0 = until stopped.
     */
    public float loops = 1.0f;

    /**
     * A field-for-field copy via the wire round-trip — automatically covers every serialised field, so
     * new fields can never be forgotten. Use before any mutation of a def shared with a catalogue/pack map.
     */
    public EmoteDef copy() {
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(256);
            write(new java.io.DataOutputStream(bytes));
            return read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray())));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e); // in-memory streams cannot actually throw
        }
    }

    public void write(DataOutput out) throws IOException {
        out.writeUTF(id);
        out.writeUTF(name);
        out.writeUTF(gifHash);
        out.writeInt(gifSize);
        out.writeUTF(bubbleHash);
        out.writeInt(bubbleSize);
        out.writeUTF(soundHash);
        out.writeInt(soundSize);
        out.writeDouble(price);
        out.writeUTF(priceText);
        out.writeBoolean(canUse);
        out.writeBoolean(owned);
        out.writeBoolean(purchasable);
        out.writeFloat(scale);
        out.writeFloat(anchor);
        out.writeFloat(offsetX);
        out.writeFloat(offsetY);
        out.writeFloat(offsetZ);
        out.writeFloat(gifScale);
        out.writeFloat(gifOffsetX);
        out.writeFloat(gifOffsetY);
        out.writeFloat(soundVolume);
        out.writeFloat(soundPitch);
        out.writeInt(soundRange);
        out.writeFloat(loops);
    }

    public static EmoteDef read(DataInput in) throws IOException {
        EmoteDef d = new EmoteDef();
        d.id = in.readUTF();
        d.name = in.readUTF();
        d.gifHash = in.readUTF();
        d.gifSize = in.readInt();
        d.bubbleHash = in.readUTF();
        d.bubbleSize = in.readInt();
        d.soundHash = in.readUTF();
        d.soundSize = in.readInt();
        d.price = in.readDouble();
        d.priceText = in.readUTF();
        d.canUse = in.readBoolean();
        d.owned = in.readBoolean();
        d.purchasable = in.readBoolean();
        d.scale = in.readFloat();
        d.anchor = in.readFloat();
        d.offsetX = in.readFloat();
        d.offsetY = in.readFloat();
        d.offsetZ = in.readFloat();
        d.gifScale = in.readFloat();
        d.gifOffsetX = in.readFloat();
        d.gifOffsetY = in.readFloat();
        d.soundVolume = in.readFloat();
        d.soundPitch = in.readFloat();
        d.soundRange = in.readInt();
        d.loops = in.readFloat();
        return d;
    }
}
