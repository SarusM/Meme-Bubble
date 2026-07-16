package io.github.sarusm.meme.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Mth;

/**
 * The viewer-side preferences ({@code <gameDir>/meme/client.properties}) — this is the player's own
 * control over OTHER players' emotes: a master volume, a global kill-switch, and per-player volume/mute.
 * Muting hides the bubble AND silences the sound; volumes only scale the sound. Nothing here touches the
 * server — it's personal filtering, like the volume sliders in voice-chat mods.
 */
public final class ClientPrefs {
    private static final Properties PROPS = new Properties();

    private ClientPrefs() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getGameDir().resolve("meme").resolve("client.properties");
    }

    public static void load() {
        PROPS.clear();
        if (!Files.isRegularFile(file())) {
            return;
        }
        try (InputStream in = Files.newInputStream(file())) {
            PROPS.load(in);
        } catch (IOException e) {
            MemeClient.LOGGER.warn("Could not read client.properties", e);
        }
    }

    public static void save() {
        try {
            Files.createDirectories(file().getParent());
            try (OutputStream out = Files.newOutputStream(file())) {
                PROPS.store(out, "Meme client preferences (per-player emote volume/mute)");
            }
        } catch (IOException e) {
            MemeClient.LOGGER.warn("Could not save client.properties", e);
        }
    }

    // --- Global ---

    /** 0..2 multiplier on every emote sound. */
    public static double masterVolume() {
        return getDouble("masterVolume", 1.0);
    }

    public static void setMasterVolume(double value) {
        PROPS.setProperty("masterVolume", format(Mth.clamp(value, 0.0, 2.0)));
    }

    /** The kill-switch: no bubbles, no sounds, from anyone. */
    public static boolean muteAll() {
        return Boolean.parseBoolean(PROPS.getProperty("muteAll", "false"));
    }

    public static void setMuteAll(boolean value) {
        PROPS.setProperty("muteAll", String.valueOf(value));
    }

    // --- Per player ---

    /** 0..2 multiplier for one player's emote sounds. */
    public static double volumeFor(UUID player) {
        return getDouble("volume." + player, 1.0);
    }

    public static void setVolumeFor(UUID player, double value) {
        PROPS.setProperty("volume." + player, format(Mth.clamp(value, 0.0, 2.0)));
    }

    public static boolean isMuted(UUID player) {
        return Boolean.parseBoolean(PROPS.getProperty("mute." + player, "false"));
    }

    public static void setMuted(UUID player, boolean value) {
        if (value) {
            PROPS.setProperty("mute." + player, "true");
        } else {
            PROPS.remove("mute." + player);
        }
    }

    // --- Quick-access wheel (pages × 8 slots around the center) + per-emote hotkeys ---
    // Stored globally by emote id: a slot simply shows as unavailable on servers without that id.

    public static final int WHEEL_PAGES = 4;
    public static final int WHEEL_SLOTS = 8;

    /** The emote id bound to a wheel slot; empty when free. */
    public static String wheelSlot(int page, int slot) {
        return PROPS.getProperty("wheel." + page + "." + slot, "");
    }

    public static void setWheelSlot(int page, int slot, String emoteId) {
        if (emoteId == null || emoteId.isEmpty()) {
            PROPS.remove("wheel." + page + "." + slot);
        } else {
            PROPS.setProperty("wheel." + page + "." + slot, emoteId);
        }
        save();
    }

    /** GLFW key code bound to the emote, or -1. */
    public static int hotkeyFor(String emoteId) {
        try {
            return Integer.parseInt(PROPS.getProperty("hotkey." + emoteId, "-1"));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static void setHotkey(String emoteId, int keyCode) {
        if (keyCode <= 0) {
            PROPS.remove("hotkey." + emoteId);
        } else {
            // One key -> one emote: steal the key from any emote that had it.
            for (String other : hotkeys().keySet()) {
                if (hotkeyFor(other) == keyCode) {
                    PROPS.remove("hotkey." + other);
                }
            }
            PROPS.setProperty("hotkey." + emoteId, String.valueOf(keyCode));
        }
        save();
    }

    /** Every bound hotkey: emote id -> GLFW key code. */
    public static Map<String, Integer> hotkeys() {
        Map<String, Integer> out = new HashMap<>();
        for (String key : PROPS.stringPropertyNames()) {
            if (key.startsWith("hotkey.")) {
                try {
                    out.put(key.substring("hotkey.".length()), Integer.parseInt(PROPS.getProperty(key)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return out;
    }

    // --- Chosen speech bubble (global across servers, like the wheel bindings) ---

    /** The player's picked bubble: "" = default (emote's own), "none" = No bubble, or a catalogue hash. */
    public static String bubble() {
        return PROPS.getProperty("bubble", "");
    }

    public static void setBubble(String choice) {
        if (choice == null || choice.isEmpty()) {
            PROPS.remove("bubble");
        } else {
            PROPS.setProperty("bubble", choice);
        }
        save();
    }

    // --- Derived ---

    /** Bubble + sound completely hidden for this emitter? */
    public static boolean isHidden(UUID player) {
        return muteAll() || isMuted(player);
    }

    /** The final sound multiplier for this emitter (0 when muted). */
    public static float effectiveVolume(UUID player) {
        if (isHidden(player)) {
            return 0.0f;
        }
        return (float) (masterVolume() * volumeFor(player));
    }

    private static double getDouble(String key, double def) {
        try {
            String value = PROPS.getProperty(key);
            return value == null ? def : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
