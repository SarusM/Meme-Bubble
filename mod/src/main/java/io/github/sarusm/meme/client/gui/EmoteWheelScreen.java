package io.github.sarusm.meme.client.gui;

import io.github.sarusm.meme.client.ClientEmotes;
import io.github.sarusm.meme.client.ClientPrefs;
import io.github.sarusm.meme.client.EmoteTextures;
import io.github.sarusm.meme.client.net.ClientNet;
import io.github.sarusm.meme.common.EmoteDef;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

/**
 * The quick-access emote wheel (keybind {@code B}): a 3×3 grid of big slots around a page switcher —
 * click a bound emote to play it instantly. Slots are assigned in the {@code G} panel (select an emote,
 * click a wheel cell) or right here: left-click an EMPTY slot opens the full panel, right-click any slot
 * clears it. Bindings live in {@link ClientPrefs} (per emote id, shared across servers); a slot whose
 * emote doesn't exist on this server shows dimmed.
 */
public class EmoteWheelScreen extends Screen {
    private static final int GAP = 8;

    /** Sticky current page across opens (client-session lifetime). */
    private static int page;

    private int cell;
    private int gridX;
    private int gridY;

    public EmoteWheelScreen() {
        super(Component.translatable("screen.meme.wheel.title"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        cell = Math.max(56, Math.min(90, (this.height - 110) / 3 - GAP));
        int gridSize = 3 * cell + 2 * GAP;
        gridX = (this.width - gridSize) / 2;
        gridY = (this.height - 40 - gridSize) / 2;

        // Page arrows inside the center cell.
        int cx = gridX + cell + GAP;
        int cy = gridY + cell + GAP;
        this.addRenderableWidget(Button.builder(Component.literal("◀"), b -> flipPage(-1))
                .bounds(cx + 4, cy + cell / 2 - 10, 20, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("▶"), b -> flipPage(1))
                .bounds(cx + cell - 24, cy + cell / 2 - 10, 20, 20).build());

        int buttonsY = gridY + gridSize + 12;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> this.onClose())
                .bounds(this.width / 2 - 155, buttonsY, 150, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.meme.all_emotes"),
                b -> this.minecraft.setScreen(new EmotesScreen(null)))
                .bounds(this.width / 2 + 5, buttonsY, 150, 20).build());
    }

    private void flipPage(int direction) {
        page = Math.floorMod(page + direction, ClientPrefs.WHEEL_PAGES);
    }

    /** Grid cell (row, col) of a slot index 0..7 (center cell excluded). */
    private static int[] slotCell(int slot) {
        int idx = slot >= 4 ? slot + 1 : slot; // skip the center (grid index 4)
        return new int[] {idx / 3, idx % 3};
    }

    private int[] cellOrigin(int row, int col) {
        return new int[] {gridX + col * (cell + GAP), gridY + row * (cell + GAP)};
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        long now = System.currentTimeMillis();

        for (int slot = 0; slot < ClientPrefs.WHEEL_SLOTS; slot++) {
            int[] rc = slotCell(slot);
            int[] origin = cellOrigin(rc[0], rc[1]);
            renderSlot(graphics, slot, origin[0], origin[1], mouseX, mouseY, now);
        }

        // Center: page indicator (the arrows are real widgets).
        int cx = gridX + cell + GAP;
        int cy = gridY + cell + GAP;
        graphics.drawCenteredString(this.font, String.valueOf(page + 1),
                cx + cell / 2, cy + cell / 2 - 4, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font, Component.translatable("screen.meme.page").getString(),
                cx + cell / 2, cy + cell / 2 + 10, 0xFF777777);

        graphics.drawCenteredString(this.font,
                Component.translatable("screen.meme.wheel_hint").getString(),
                this.width / 2, gridY - 14, 0xFF999999);
    }

    private void renderSlot(GuiGraphics graphics, int slot, int x, int y, int mouseX, int mouseY, long now) {
        boolean hovered = mouseX >= x && mouseX < x + cell && mouseY >= y && mouseY < y + cell;
        graphics.fill(x, y, x + cell, y + cell, 0x90101018);
        graphics.renderOutline(x, y, cell, cell, hovered ? 0xFFFFFFFF : 0x60FFFFFF);

        String emoteId = ClientPrefs.wheelSlot(page, slot);
        if (emoteId.isEmpty()) {
            graphics.drawCenteredString(this.font, "+", x + cell / 2, y + cell / 2 - 4, 0xFF666666);
            return;
        }
        EmoteDef def = ClientEmotes.anyDef(emoteId); // catalogue, or the player's own pack when allowed
        if (def == null) {
            // Bound on another server / removed — keep the binding but show it dimmed.
            graphics.drawCenteredString(this.font, "?", x + cell / 2, y + cell / 2 - 8, 0xFF884444);
            graphics.drawCenteredString(this.font, this.font.plainSubstrByWidth(emoteId, cell - 6),
                    x + cell / 2, y + cell - 11, 0xFF666666);
            return;
        }

        EmoteTextures.Gif gif = EmoteTextures.gif(def.gifHash);
        if (gif != null) {
            int pad = 4;
            int size = cell - 2 * pad - 10; // leave a name strip at the bottom
            double fit = Math.min((double) size / gif.width, (double) size / gif.height);
            int dw = Math.max(1, (int) (gif.width * fit));
            int dh = Math.max(1, (int) (gif.height * fit));
            graphics.blit(RenderPipelines.GUI_TEXTURED, gif.frameAt(now),
                    x + (cell - dw) / 2, y + pad + (size - dh) / 2,
                    0.0f, 0.0f, dw, dh, gif.width, gif.height, gif.width, gif.height);
        } else if (def.gifHash.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    this.font.plainSubstrByWidth("♪ " + def.name, cell - 6),
                    x + cell / 2, y + cell / 2 - 8, 0xFFFFDD66);
        } else {
            graphics.drawCenteredString(this.font, "…", x + cell / 2, y + cell / 2 - 8, 0xFFAAAAAA);
        }
        if (!def.gifHash.isEmpty()) {
            // Sound-only cells skip the strip — their name is already in the "♪ name" label.
            graphics.drawCenteredString(this.font, this.font.plainSubstrByWidth(def.name, cell - 6),
                    x + cell / 2, y + cell - 11, 0xFFFFFFFF);
        }

        if (!def.canUse) {
            graphics.fill(x, y, x + cell, y + cell, 0x60000000);
            graphics.drawString(this.font, def.purchasable ? "$" : "✖",
                    x + cell - 9, y + 3, def.purchasable ? 0xFFFFC800 : 0xFFAAAAAA);
        }
        int hotkey = ClientPrefs.hotkeyFor(emoteId);
        if (hotkey > 0) {
            graphics.drawString(this.font,
                    InputConstants.Type.KEYSYM.getOrCreate(hotkey).getDisplayName().getString(),
                    x + 3, y + 3, 0xFF88FF88);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        for (int slot = 0; slot < ClientPrefs.WHEEL_SLOTS; slot++) {
            int[] rc = slotCell(slot);
            int[] origin = cellOrigin(rc[0], rc[1]);
            if (event.x() < origin[0] || event.x() >= origin[0] + cell
                    || event.y() < origin[1] || event.y() >= origin[1] + cell) {
                continue;
            }
            String emoteId = ClientPrefs.wheelSlot(page, slot);
            if (event.button() == 1) {
                ClientPrefs.setWheelSlot(page, slot, "");
                return true;
            }
            if (event.button() == 0) {
                EmoteDef def = emoteId.isEmpty() ? null : ClientEmotes.anyDef(emoteId);
                if (def != null && def.canUse) {
                    ClientNet.playAny(def.id);
                    this.onClose();
                } else {
                    // Empty / locked / unknown here -> the full panel (locked opens on that emote's shop entry).
                    this.minecraft.setScreen(new EmotesScreen(null, emoteId));
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(null);
    }
}
