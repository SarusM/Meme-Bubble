package io.github.sarusm.meme.client;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.sarusm.meme.client.gui.EmoteWheelScreen;
import io.github.sarusm.meme.client.gui.EmotesScreen;
import io.github.sarusm.meme.client.net.ClientNet;
import io.github.sarusm.meme.client.net.EmotesPayload;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Meme client entry point. Registers the {@code meme:main} plugin-message payload (both
 * directions — the server side is the Meme PAPER PLUGIN, not a Fabric mod), the world-billboard
 * renderer, the panel keybind ({@code G}), the quick-access wheel keybind ({@code B}), per-emote hotkeys,
 * and the join/disconnect lifecycle.
 *
 * <p>Handshake is belt-and-suspenders: the server pushes the catalogue as soon as the client's channel
 * registration arrives, AND the client HELLOs on join with retries every 2s (up to 20s) until the list
 * lands — so emotes are visible right after joining with no manual refresh.
 */
public class MemeClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("meme");

    /** Ticks since joining a world; -1 = not in a world. Drives the HELLO retry schedule. */
    private static int joinTicks = -1;
    /** HELLO retry cadence/window: every 40 ticks until 400 (10 tries over 20 s). */
    private static final int HELLO_RETRY_INTERVAL = 40;
    private static final int HELLO_RETRY_WINDOW = 400;

    /** Hotkey edge detection: codes currently held down. */
    private static final Set<Integer> HELD_KEYS = new HashSet<>();

    @Override
    public void onInitializeClient() {
        ClientPrefs.load();

        // First launch: unpack the bundled starter pack, then pre-import all local packs off-thread —
        // a fresh pack is ~84 MB of hashing + cache writes, and the join-time rescan (render thread)
        // must hit a warm memo instead of doing that work itself.
        LocalEmotes.installBundledPack();
        Thread prewarm = new Thread(LocalEmotes::rescan, "meme-pack-prewarm");
        prewarm.setDaemon(true);
        prewarm.start();

        PayloadTypeRegistry.playS2C().register(EmotesPayload.TYPE, EmotesPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(EmotesPayload.TYPE, EmotesPayload.STREAM_CODEC);

        ClientPlayNetworking.registerGlobalReceiver(EmotesPayload.TYPE, (payload, context) ->
                context.client().execute(() -> ClientNet.handle(payload.data())));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ClientNet.reset();
            LocalEmotes.rescan(); // the player's own pack, ready in case this server allows it
            joinTicks = 0;
            ClientNet.hello(); // no-op on servers without the plugin (unknown channels are ignored)
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            joinTicks = -1;
            ClientEmotes.clear();
        });

        EmoteRenderer.register();
        registerKeybindsAndTick();

        LOGGER.info("Meme client loaded.");
    }

    private static void registerKeybindsAndTick() {
        KeyMapping openPanel = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.meme.open_panel",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G, // H and K are taken by ClashBowler, J by FlashCam in the user's instance
                KeyMapping.Category.MISC));
        KeyMapping openWheel = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.meme.open_wheel",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openPanel.consumeClick()) {
                client.setScreen(new EmotesScreen(client.screen));
            }
            while (openWheel.consumeClick()) {
                client.setScreen(new EmoteWheelScreen());
            }
            if (client.level == null) {
                return;
            }
            tickHelloRetries();
            tickHotkeys(client);
        });
    }

    /** Re-HELLO until the server's catalogue arrives (covers packets lost during the join handshake). */
    private static void tickHelloRetries() {
        if (joinTicks < 0) {
            return;
        }
        joinTicks++;
        if (!ClientEmotes.connected() && joinTicks <= HELLO_RETRY_WINDOW
                && joinTicks % HELLO_RETRY_INTERVAL == 0) {
            ClientNet.hello();
        }
    }

    /** Per-emote hotkeys ({@link ClientPrefs#hotkeys()}): play on key-down edge, only in-game. */
    private static void tickHotkeys(Minecraft client) {
        if (client.screen != null || !ClientEmotes.connected()) {
            return;
        }
        for (Map.Entry<String, Integer> entry : ClientPrefs.hotkeys().entrySet()) {
            int code = entry.getValue();
            boolean down = InputConstants.isKeyDown(client.getWindow(), code);
            if (down) {
                if (HELD_KEYS.add(code)) {
                    ClientNet.playAny(entry.getKey()); // server emote, or the player's own pack
                }
            } else {
                HELD_KEYS.remove(code);
            }
        }
    }
}
