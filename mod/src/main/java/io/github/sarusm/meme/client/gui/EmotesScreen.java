package io.github.sarusm.meme.client.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

import io.github.sarusm.meme.client.AssetCache;
import io.github.sarusm.meme.client.ClientEmotes;
import io.github.sarusm.meme.client.ClientPrefs;
import io.github.sarusm.meme.client.EmoteTextures;
import io.github.sarusm.meme.client.LocalEmotes;
import io.github.sarusm.meme.client.net.ClientNet;
import io.github.sarusm.meme.common.EmoteDef;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The Meme panel (keybind {@code G}; the world keeps running behind it). Two tabs:
 *
 * <p><b>Emotes</b> — the server's catalogue as an animated thumbnail grid (assets appear as they download),
 * plus — when the server enables {@code player-emotes} — the player's OWN pack ({@link LocalEmotes},
 * cyan-striped cells), with per-emote status (available / price / locked), Play / Buy / Stop actions
 * (validated server-side),
 * a per-emote HOTKEY binder ("Key: …" — click, press a key; ESC cancels), and the quick-access WHEEL
 * editor: select an emote in the grid, then click a wheel cell to seat it there (right-click clears).
 * The same wheel opens in-game on {@code B} ({@link EmoteWheelScreen}).
 *
 * <p><b>Players</b> — the viewer's own controls over OTHER players' emotes: master volume, a mute-all
 * kill-switch, and per-player volume slider + mute toggle (bubble AND sound). Purely client-side
 * ({@link ClientPrefs}), like the volume controls of voice-chat mods.
 */
public class EmotesScreen extends Screen {
    private static final int GRID_CELL = 46;
    private static final int GRID_GAP = 3;
    private static final int LIST_ITEM_HEIGHT = 24;
    private static final int WHEEL_GAP = 3;

    private final Screen parent;
    private final String preselectId;

    private int tab;               // 0 = emotes, 1 = players
    private String selectedId = "";
    private int gridScroll;
    private int knownRevision = -1;
    /** True while the "Key:" button waits for the next key press. */
    private boolean listeningForKey;
    /** Sticky wheel page shown in the mini editor (shared feel with the B screen, own counter). */
    private static int wheelPage;

    // Emotes-tab geometry (recomputed in init)
    private int gridX;
    private int gridY;
    private int gridW;
    private int gridH;
    private int rightX;
    private int rightW;
    private int infoY;
    private int wheelX;
    private int wheelY;
    private int wheelCell;

    public EmotesScreen(Screen parent) {
        this(parent, "");
    }

    /** Opens with {@code preselectId} selected in the grid (used by the wheel's "locked slot" click). */
    public EmotesScreen(Screen parent, String preselectId) {
        super(Component.translatable("screen.meme.panel.title"));
        this.parent = parent;
        this.preselectId = preselectId == null ? "" : preselectId;
        if (!this.preselectId.isEmpty()) {
            this.selectedId = this.preselectId;
        }
        LocalEmotes.rescanAsync(); // pick up files dropped into the pack folder while the game runs
    }

    /** The selected/shown emote def: the server catalogue, or the player's own pack when allowed. */
    private static EmoteDef anyDef(String id) {
        return ClientEmotes.anyDef(id);
    }

    /** A player's-own-pack emote (shown in the grid but absent from the server catalogue)? */
    private static boolean isLocal(EmoteDef def) {
        return def != null && ClientEmotes.def(def.id) == null;
    }

    @Override
    public boolean isPauseScreen() {
        return false; // keep the world running so a played emote is visible right behind the panel
    }

    @Override
    protected void init() {
        knownRevision = ClientEmotes.revision();
        listeningForKey = false;
        int top = 46;
        int bottom = this.height - 30;

        // Tabs
        Button tabEmotes = Button.builder(Component.translatable("screen.meme.tab.emotes"), b -> setTab(0))
                .bounds(10, 20, 100, 20).build();
        tabEmotes.active = tab != 0;
        this.addRenderableWidget(tabEmotes);
        Button tabPlayers = Button.builder(Component.translatable("screen.meme.tab.players"), b -> setTab(1))
                .bounds(114, 20, 100, 20).build();
        tabPlayers.active = tab != 1;
        this.addRenderableWidget(tabPlayers);

        if (tab == 0) {
            initEmotesTab(top, bottom);
        } else {
            initPlayersTab(top, bottom);
        }

        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose())
                .bounds(this.width / 2 - 75, this.height - 26, 150, 20).build());
    }

    private void setTab(int newTab) {
        tab = newTab;
        this.rebuildWidgets();
    }

    // ----------------------------------------------------------------------------------------------------
    // Emotes tab
    // ----------------------------------------------------------------------------------------------------

    private void initEmotesTab(int top, int bottom) {
        List<EmoteDef> emotes = ClientEmotes.allWithLocal();
        if ((selectedId.isEmpty() || anyDef(selectedId) == null) && !emotes.isEmpty()) {
            selectedId = emotes.get(0).id;
        }

        gridX = 10;
        gridW = (int) (this.width * 0.55) - 16;
        gridY = top;
        gridH = bottom - top;

        rightX = gridX + gridW + 12;
        rightW = this.width - 8 - rightX;
        infoY = top;

        int controlW = Math.max(150, rightW - 20);
        int controlX = rightX + (rightW - controlW) / 2;
        int y = infoY + 38; // info text lines are drawn above the widgets

        EmoteDef def = anyDef(selectedId);

        // Hotkey binder: click -> press a key (ESC cancels), Reset clears.
        Button bind = Button.builder(bindLabel(def), b -> {
            listeningForKey = !listeningForKey;
            b.setMessage(bindLabel(anyDef(selectedId)));
        }).bounds(controlX, y, controlW - 54, 20).build();
        bind.active = def != null;
        this.addRenderableWidget(bind);
        Button reset = Button.builder(Component.translatable("screen.meme.reset"), b -> {
            if (def != null) {
                ClientPrefs.setHotkey(def.id, -1);
                this.rebuildWidgets();
            }
        }).bounds(controlX + controlW - 50, y, 50, 20).build();
        reset.active = def != null && ClientPrefs.hotkeyFor(def.id) > 0;
        this.addRenderableWidget(reset);
        y += 24;

        Button play = Button.builder(Component.translatable("screen.meme.play"), b -> {
            if (def != null) {
                ClientNet.playAny(def.id); // routes to C2S_PLAY or C2S_PLAY_LOCAL
            }
        }).bounds(controlX, y, controlW, 20).build();
        play.active = def != null && def.canUse;
        this.addRenderableWidget(play);
        y += 24;

        if (def != null && def.purchasable) {
            this.addRenderableWidget(Button.builder(
                    Component.translatable("screen.meme.buy", def.priceText),
                    b -> ClientNet.purchase(def.id)).bounds(controlX, y, controlW, 20).build());
            y += 24;
        }

        this.addRenderableWidget(Button.builder(Component.translatable("screen.meme.stop_self"),
                b -> ClientNet.stopSelf()).bounds(controlX, y, controlW, 20).build());
        y += 24;

        this.addRenderableWidget(Button.builder(Component.translatable("screen.meme.refresh"), b -> {
            LocalEmotes.rescanAsync();
            ClientNet.hello();
        }).bounds(controlX, y, controlW, 20).build());
        y += 24;

        // My speech bubble — always available (Round 10): the picker offers the player's OWN bubbles for
        // client-side plays even offline; the server catalogue joins in when that server enables it.
        this.addRenderableWidget(Button.builder(Component.translatable("screen.meme.my_bubble"),
                b -> this.minecraft.setScreen(new BubbleScreen(this)))
                .bounds(controlX, y, controlW, 20).build());
        y += 24;

        // Quick-access wheel editor: a 3×3 grid filling ALL the space left under the buttons.
        int labelSpace = 14;
        int availH = bottom - y - labelSpace - 2 * WHEEL_GAP;
        int availW = rightW - 4 - 2 * WHEEL_GAP;
        wheelCell = Math.max(22, Math.min(availH / 3, availW / 3));
        int wheelSize = 3 * wheelCell + 2 * WHEEL_GAP;
        wheelX = rightX + (rightW - wheelSize) / 2;
        // Center the grid vertically in whatever remains (bottom-pinning left a dead gap on tall screens).
        wheelY = y + labelSpace + Math.max(0, (bottom - y - labelSpace - wheelSize) / 2);

        int cx = wheelX + wheelCell + WHEEL_GAP;
        int cy = wheelY + wheelCell + WHEEL_GAP;
        int arrowW = Mth.clamp(wheelCell / 2 - 4, 12, 26);
        int arrowH = wheelCell >= 44 ? 20 : 16;
        this.addRenderableWidget(Button.builder(Component.literal("◀"), b -> {
            wheelPage = Math.floorMod(wheelPage - 1, ClientPrefs.WHEEL_PAGES);
        }).bounds(cx + wheelCell / 2 - arrowW - 2, cy + wheelCell / 2 - arrowH / 2, arrowW, arrowH).build());
        this.addRenderableWidget(Button.builder(Component.literal("▶"), b -> {
            wheelPage = Math.floorMod(wheelPage + 1, ClientPrefs.WHEEL_PAGES);
        }).bounds(cx + wheelCell / 2 + 2, cy + wheelCell / 2 - arrowH / 2, arrowW, arrowH).build());
    }

    private static Component bindLabel(EmoteDef def) {
        if (def == null) {
            return Component.translatable("screen.meme.hotkey", "—");
        }
        int code = ClientPrefs.hotkeyFor(def.id);
        Component name = code > 0
                ? InputConstants.Type.KEYSYM.getOrCreate(code).getDisplayName()
                : Component.translatable("screen.meme.hotkey.unbound");
        return Component.translatable("screen.meme.hotkey", name);
    }

    /** Capture the next key press for the hotkey binder (ESC = cancel without changing). */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (listeningForKey && tab == 0) {
            listeningForKey = false;
            EmoteDef def = anyDef(selectedId);
            if (def != null && event.key() != GLFW.GLFW_KEY_ESCAPE) {
                ClientPrefs.setHotkey(def.id, event.key());
            }
            this.rebuildWidgets();
            return true;
        }
        return super.keyPressed(event);
    }

    // ----------------------------------------------------------------------------------------------------
    // Players tab
    // ----------------------------------------------------------------------------------------------------

    private void initPlayersTab(int top, int bottom) {
        int listW = this.width - 20;
        int rowW = Math.min(400, listW - 20);
        ControlList list = new ControlList(this.minecraft, listW, bottom - top, top, LIST_ITEM_HEIGHT, rowW);
        list.setRectangle(listW, bottom - top, 10, top);

        String volumeLabel = Component.translatable("screen.meme.volume").getString();
        list.add(new Row(Component.translatable("screen.meme.master_volume").getString(), List.of(
                new PanelSlider(rowW - 170, volumeLabel, 0.0, 2.0,
                        ClientPrefs::masterVolume, ClientPrefs::setMasterVolume))));
        list.add(new Row(Component.translatable("screen.meme.all_emotes").getString(), List.of(
                toggle(rowW - 170, () -> !ClientPrefs.muteAll(), on -> ClientPrefs.setMuteAll(!on),
                        Component.translatable("screen.meme.toggle.on").getString(),
                        Component.translatable("screen.meme.toggle.off").getString()))));

        Minecraft mc = Minecraft.getInstance();
        UUID self = mc.player == null ? null : mc.player.getUUID();
        List<PlayerInfo> players = new ArrayList<>(
                mc.getConnection() == null ? List.of() : mc.getConnection().getOnlinePlayers());
        players.sort(Comparator.comparing(p -> p.getProfile().name().toLowerCase(Locale.ROOT)));
        for (PlayerInfo info : players) {
            UUID id = info.getProfile().id();
            if (id == null || id.equals(self)) {
                continue;
            }
            list.add(new Row(info.getProfile().name(), List.of(
                    new PanelSlider(rowW - 170 - 84, volumeLabel, 0.0, 2.0,
                            () -> ClientPrefs.volumeFor(id), v -> ClientPrefs.setVolumeFor(id, v)),
                    toggle(80, () -> !ClientPrefs.isMuted(id), on -> ClientPrefs.setMuted(id, !on),
                            Component.translatable("screen.meme.shown").getString(),
                            Component.translatable("screen.meme.muted").getString()))));
        }
        this.addRenderableWidget(list);
    }

    private static Button toggle(int width, java.util.function.BooleanSupplier get,
            java.util.function.Consumer<Boolean> set, String onLabel, String offLabel) {
        return Button.builder(Component.literal(get.getAsBoolean() ? onLabel : offLabel), b -> {
            boolean next = !get.getAsBoolean();
            set.accept(next);
            b.setMessage(Component.literal(next ? onLabel : offLabel));
        }).bounds(0, 0, width, 20).build();
    }

    // ----------------------------------------------------------------------------------------------------
    // Rendering
    // ----------------------------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Live refresh when the server pushes catalogue changes (purchase results, price updates).
        if (knownRevision != ClientEmotes.revision()) {
            this.rebuildWidgets();
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);

        if (tab != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        List<EmoteDef> emotes = ClientEmotes.allWithLocal();

        if (!ClientEmotes.connected() && emotes.isEmpty()) {
            // No plugin AND no own pack — nothing to show. With a local pack the grid works everywhere
            // (client-side-only plays), so the red "no server" line only appears when truly empty.
            graphics.drawString(this.font, Component.translatable("screen.meme.no_server").getString(),
                    gridX + 4, gridY + 8, 0xFFFF8888);
        } else if (emotes.isEmpty()) {
            graphics.drawString(this.font, Component.translatable("screen.meme.empty").getString(),
                    gridX + 4, gridY + 8, 0xFFAAAAAA);
            if (ClientEmotes.playerEmotesAllowed()) {
                graphics.drawString(this.font, this.font.plainSubstrByWidth(
                        Component.translatable("screen.meme.local_hint").getString(), gridW - 8),
                        gridX + 4, gridY + 20, 0xFF55CCFF);
            }
        }

        renderGrid(graphics, emotes, mouseX, mouseY, now);
        renderInfo(graphics);
        renderWheel(graphics, mouseX, mouseY, now);

        if (listeningForKey) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("screen.meme.press_key").getString(),
                    rightX + rightW / 2, infoY + 28, 0xFFFFC800);
        }
    }

    private void renderGrid(GuiGraphics graphics, List<EmoteDef> emotes, int mouseX, int mouseY, long now) {
        graphics.fill(gridX - 2, gridY - 2, gridX + gridW + 2, gridY + gridH + 2, 0x66000000);
        graphics.enableScissor(gridX, gridY, gridX + gridW, gridY + gridH);
        int cols = Math.max(1, gridW / (GRID_CELL + GRID_GAP));
        int first = gridScroll * cols;
        for (int i = first; i < emotes.size(); i++) {
            int col = (i - first) % cols;
            int row = (i - first) / cols;
            int cx = gridX + col * (GRID_CELL + GRID_GAP);
            int cy = gridY + row * (GRID_CELL + GRID_GAP);
            if (cy > gridY + gridH) {
                break;
            }
            EmoteDef def = emotes.get(i);
            graphics.fill(cx, cy, cx + GRID_CELL, cy + GRID_CELL, 0x50101018);
            drawThumb(graphics, def, cx + 2, cy + 2, GRID_CELL - 4, now);

            if (isLocal(def)) {
                graphics.fill(cx, cy, cx + GRID_CELL, cy + 3, 0xFF00AACC); // cyan strip = player's own pack
            }
            if (!def.canUse) {
                graphics.fill(cx, cy, cx + GRID_CELL, cy + GRID_CELL, 0x60000000);
                graphics.drawString(this.font, def.purchasable ? "$" : "✖",
                        cx + GRID_CELL - 9, cy + 2, def.purchasable ? 0xFFFFC800 : 0xFFAAAAAA);
            }

            boolean hovered = mouseX >= cx && mouseX < cx + GRID_CELL
                    && mouseY >= cy && mouseY < cy + GRID_CELL
                    && mouseY >= gridY && mouseY < gridY + gridH;
            if (def.id.equals(selectedId)) {
                graphics.renderOutline(cx, cy, GRID_CELL, GRID_CELL, 0xFFFFC800); // gold = selected
            } else if (hovered) {
                graphics.renderOutline(cx, cy, GRID_CELL, GRID_CELL, 0xAAFFFFFF);
            }
        }
        graphics.disableScissor();
    }

    /** One aspect-fit thumbnail: the current animation frame, "♪" for sound-only, "…" while downloading. */
    private void drawThumb(GuiGraphics graphics, EmoteDef def, int x, int y, int size, long now) {
        if (def.gifHash.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    this.font.plainSubstrByWidth("♪ " + def.name, size),
                    x + size / 2, y + size / 2 - 4, 0xFFFFDD66);
            return;
        }
        EmoteTextures.Gif gif = EmoteTextures.gif(def.gifHash);
        if (gif == null) {
            // "!" only for a real decode failure — while downloading OR background-decoding it's "…".
            graphics.drawCenteredString(this.font, EmoteTextures.failed(def.gifHash) ? "!" : "…",
                    x + size / 2, y + size / 2 - 4, 0xFFAAAAAA);
            return;
        }
        double fit = Math.min((double) size / gif.width, (double) size / gif.height);
        int dw = Math.max(1, (int) (gif.width * fit));
        int dh = Math.max(1, (int) (gif.height * fit));
        graphics.blit(RenderPipelines.GUI_TEXTURED, gif.frameAt(now), x + (size - dw) / 2, y + (size - dh) / 2,
                0.0f, 0.0f, dw, dh, gif.width, gif.height, gif.width, gif.height);
    }

    private void renderInfo(GuiGraphics graphics) {
        EmoteDef def = anyDef(selectedId);
        if (def == null) {
            return;
        }
        boolean local = isLocal(def);
        int x = rightX + 4;
        int y = infoY;
        graphics.drawString(this.font,
                this.font.plainSubstrByWidth(def.name + "  (" + def.id + ")", rightW - 8), x, y, 0xFFFFFFFF);
        y += 11;
        String status;
        int color;
        if (local) {
            status = Component.translatable("screen.meme.status.local").getString();
            color = 0xFF55CCFF;
        } else if (def.canUse) {
            status = Component.translatable(def.owned
                    ? "screen.meme.status.owned" : "screen.meme.status.available").getString();
            color = 0xFF55FF55;
        } else if (def.purchasable) {
            status = Component.translatable("screen.meme.status.price", def.priceText).getString();
            color = 0xFFFFC800;
        } else {
            status = Component.translatable("screen.meme.status.locked").getString();
            color = 0xFFAAAAAA;
        }
        List<String> extras = new ArrayList<>();
        if (def.gifHash.isEmpty()) {
            extras.add(Component.translatable("screen.meme.status.sound_only").getString());
        } else if (!def.soundHash.isEmpty()) {
            extras.add(Component.translatable("screen.meme.status.with_sound").getString());
        }
        if (!local && ((!def.gifHash.isEmpty() && !AssetCache.has(def.gifHash))
                || (!def.bubbleHash.isEmpty() && !AssetCache.has(def.bubbleHash))
                || (!def.soundHash.isEmpty() && !AssetCache.has(def.soundHash)))) {
            extras.add(Component.translatable("screen.meme.status.downloading").getString());
        }
        String line = status + (extras.isEmpty() ? "" : " · " + String.join(", ", extras));
        graphics.drawString(this.font, this.font.plainSubstrByWidth(line, rightW - 8), x, y, color);
    }

    /** The mini wheel editor: click a cell to seat the selected emote there, right-click to clear. */
    private void renderWheel(GuiGraphics graphics, int mouseX, int mouseY, long now) {
        graphics.drawCenteredString(this.font,
                this.font.plainSubstrByWidth(
                        Component.translatable("screen.meme.wheel_editor").getString(), rightW),
                wheelX + (3 * wheelCell + 2 * WHEEL_GAP) / 2, wheelY - 12, 0xFFFFFF55);
        boolean big = wheelCell >= 40; // room for a name strip and status markers
        for (int slot = 0; slot < ClientPrefs.WHEEL_SLOTS; slot++) {
            int[] rc = wheelSlotCell(slot);
            int x = wheelX + rc[1] * (wheelCell + WHEEL_GAP);
            int y = wheelY + rc[0] * (wheelCell + WHEEL_GAP);
            boolean hovered = mouseX >= x && mouseX < x + wheelCell && mouseY >= y && mouseY < y + wheelCell;
            graphics.fill(x, y, x + wheelCell, y + wheelCell, 0x90101018);
            graphics.renderOutline(x, y, wheelCell, wheelCell, hovered ? 0xFFFFFFFF : 0x60FFFFFF);

            String emoteId = ClientPrefs.wheelSlot(wheelPage, slot);
            if (emoteId.isEmpty()) {
                graphics.drawCenteredString(this.font, "+",
                        x + wheelCell / 2, y + wheelCell / 2 - 4, 0xFF666666);
                continue;
            }
            EmoteDef def = anyDef(emoteId);
            EmoteTextures.Gif gif = def == null ? null : EmoteTextures.gif(def.gifHash);
            if (gif != null) {
                int pad = 3;
                int size = wheelCell - 2 * pad - (big ? 10 : 0);
                double fit = Math.min((double) size / gif.width, (double) size / gif.height);
                int dw = Math.max(1, (int) (gif.width * fit));
                int dh = Math.max(1, (int) (gif.height * fit));
                graphics.blit(RenderPipelines.GUI_TEXTURED, gif.frameAt(now),
                        x + (wheelCell - dw) / 2, y + pad + (size - dh) / 2,
                        0.0f, 0.0f, dw, dh, gif.width, gif.height, gif.width, gif.height);
            } else if (def != null && def.gifHash.isEmpty()) {
                graphics.drawCenteredString(this.font,
                        this.font.plainSubstrByWidth("♪ " + def.name, wheelCell - 4),
                        x + wheelCell / 2, y + wheelCell / 2 - 4, 0xFFFFDD66);
            } else {
                graphics.drawCenteredString(this.font, def == null ? "?" : "…",
                        x + wheelCell / 2, y + wheelCell / 2 - 4, 0xFF888888);
            }
            if (big && def != null && !def.gifHash.isEmpty()) {
                // Sound-only cells skip the strip — their name is already in the "♪ name" label.
                graphics.drawCenteredString(this.font,
                        this.font.plainSubstrByWidth(def.name, wheelCell - 6),
                        x + wheelCell / 2, y + wheelCell - 11, 0xFFFFFFFF);
            }
            if (def != null && !def.canUse) {
                graphics.fill(x, y, x + wheelCell, y + wheelCell, 0x60000000);
                graphics.drawString(this.font, def.purchasable ? "$" : "✖",
                        x + wheelCell - 9, y + 3, def.purchasable ? 0xFFFFC800 : 0xFFAAAAAA);
            }
            if (big && def != null) {
                int hotkey = ClientPrefs.hotkeyFor(def.id);
                if (hotkey > 0) {
                    graphics.drawString(this.font,
                            InputConstants.Type.KEYSYM.getOrCreate(hotkey).getDisplayName().getString(),
                            x + 3, y + 3, 0xFF88FF88);
                }
            }
        }
        // Page number under the ◀ ▶ arrows in the center cell (the arrows are real widgets on top).
        int cx = wheelX + wheelCell + WHEEL_GAP;
        int cy = wheelY + wheelCell + WHEEL_GAP;
        int arrowH = wheelCell >= 44 ? 20 : 16;
        graphics.drawCenteredString(this.font, String.valueOf(wheelPage + 1),
                cx + wheelCell / 2, Math.min(cy + wheelCell / 2 + arrowH / 2 + 4, cy + wheelCell - 10),
                0xFFFFFFFF);
    }

    /** Grid cell (row, col) of a wheel slot index 0..7 (center cell excluded). */
    private static int[] wheelSlotCell(int slot) {
        int idx = slot >= 4 ? slot + 1 : slot;
        return new int[] {idx / 3, idx % 3};
    }

    // ----------------------------------------------------------------------------------------------------
    // Mouse
    // ----------------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (tab == 0) {
            List<EmoteDef> emotes = ClientEmotes.allWithLocal();
            double mx = event.x();
            double my = event.y();
            if (mx >= gridX && mx < gridX + gridW && my >= gridY && my < gridY + gridH) {
                int cols = Math.max(1, gridW / (GRID_CELL + GRID_GAP));
                int col = (int) ((mx - gridX) / (GRID_CELL + GRID_GAP));
                int row = (int) ((my - gridY) / (GRID_CELL + GRID_GAP));
                if (col < cols && (mx - gridX) % (GRID_CELL + GRID_GAP) <= GRID_CELL
                        && (my - gridY) % (GRID_CELL + GRID_GAP) <= GRID_CELL) {
                    int index = (gridScroll + row) * cols + col;
                    if (index >= 0 && index < emotes.size()) {
                        selectedId = emotes.get(index).id;
                        this.rebuildWidgets();
                        return true;
                    }
                }
            }
            if (wheelClicked(event)) {
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** Wheel editor cells: left = seat the selected emote, right = clear the slot. */
    private boolean wheelClicked(MouseButtonEvent event) {
        for (int slot = 0; slot < ClientPrefs.WHEEL_SLOTS; slot++) {
            int[] rc = wheelSlotCell(slot);
            int x = wheelX + rc[1] * (wheelCell + WHEEL_GAP);
            int y = wheelY + rc[0] * (wheelCell + WHEEL_GAP);
            if (event.x() < x || event.x() >= x + wheelCell
                    || event.y() < y || event.y() >= y + wheelCell) {
                continue;
            }
            if (event.button() == 1) {
                ClientPrefs.setWheelSlot(wheelPage, slot, "");
            } else if (event.button() == 0 && !selectedId.isEmpty()) {
                ClientPrefs.setWheelSlot(wheelPage, slot, selectedId);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (tab == 0 && mouseX >= gridX && mouseX < gridX + gridW
                && mouseY >= gridY && mouseY < gridY + gridH) {
            int cols = Math.max(1, gridW / (GRID_CELL + GRID_GAP));
            int totalRows = (ClientEmotes.allWithLocal().size() + cols - 1) / cols;
            int visibleRows = Math.max(1, gridH / (GRID_CELL + GRID_GAP));
            gridScroll = Mth.clamp(gridScroll - (int) Math.signum(deltaY),
                    0, Math.max(0, totalRows - visibleRows));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    @Override
    public void onClose() {
        ClientPrefs.save();
        this.minecraft.setScreen(parent);
    }

    // ----------------------------------------------------------------------------------------------------
    // Widgets
    // ----------------------------------------------------------------------------------------------------

    /** A percent slider over a getter/setter (snaps to 5%). */
    private static class PanelSlider extends AbstractSliderButton {
        private final String label;
        private final double min;
        private final double max;
        private final DoubleConsumer setter;

        PanelSlider(int width, String label, double min, double max,
                DoubleSupplier getter, DoubleConsumer setter) {
            super(0, 0, width, 20, Component.empty(),
                    Mth.clamp((getter.getAsDouble() - min) / (max - min), 0.0, 1.0));
            this.label = label;
            this.min = min;
            this.max = max;
            this.setter = setter;
            this.updateMessage();
        }

        private double snapped() {
            double raw = min + this.value * (max - min);
            return Mth.clamp(Math.round(raw / 0.05) * 0.05, min, max);
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal(label + ": " + Math.round(snapped() * 100) + "%"));
        }

        @Override
        protected void applyValue() {
            setter.accept(snapped());
        }
    }

    /** Scrollable list of labelled widget rows (label left, widgets packed to the right). */
    private static class ControlList extends ContainerObjectSelectionList<Row> {
        private final int rowWidth;

        ControlList(Minecraft mc, int width, int height, int y, int itemHeight, int rowWidth) {
            super(mc, width, height, y, itemHeight);
            this.rowWidth = rowWidth;
        }

        void add(Row row) {
            this.addEntry(row);
        }

        @Override
        public int getRowWidth() {
            return rowWidth + 10;
        }
    }

    private static class Row extends ContainerObjectSelectionList.Entry<Row> {
        private final String label;
        private final List<AbstractWidget> widgets;

        Row(String label, List<AbstractWidget> widgets) {
            this.label = label;
            this.widgets = widgets;
        }

        @Override
        public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, boolean hovered,
                float partialTick) {
            Minecraft mc = Minecraft.getInstance();
            graphics.drawString(mc.font, mc.font.plainSubstrByWidth(label, 160),
                    this.getContentX(), this.getContentY() + 6, 0xFFFFFFFF);
            int x = this.getContentX() + this.getContentWidth();
            for (int i = widgets.size() - 1; i >= 0; i--) {
                AbstractWidget widget = widgets.get(i);
                x -= widget.getWidth();
                widget.setX(x);
                widget.setY(this.getContentY());
                widget.render(graphics, mouseX, mouseY, partialTick);
                x -= 4;
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return widgets;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return widgets;
        }
    }
}
