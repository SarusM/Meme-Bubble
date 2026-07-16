package io.github.sarusm.meme.client.gui;

import java.util.ArrayList;
import java.util.List;

import io.github.sarusm.meme.client.AssetCache;
import io.github.sarusm.meme.client.ClientEmotes;
import io.github.sarusm.meme.client.ClientPrefs;
import io.github.sarusm.meme.client.EmoteTextures;
import io.github.sarusm.meme.client.net.ClientNet;
import io.github.sarusm.meme.common.EmoteProto;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

/**
 * The speech-bubble picker ("My Bubble" in the G panel): the player chooses which bubble rides with THEIR
 * emotes, from the server's catalogue ({@link ClientEmotes#bubbles()}) plus two special cells — "Default"
 * (no override, each emote keeps its own bubble) and "No bubble" (draw no bubble). The pick is global
 * across servers ({@link ClientPrefs#bubble()}) and pushed to the server ({@link ClientNet#setBubble}),
 * which applies it to the player's plays. Shown only when the server enables the choice.
 */
public class BubbleScreen extends Screen {
    /** One picker cell: {@code choice} is the wire value ("" default / "none" / a catalogue hash). */
    private record Option(String choice, String label, String bubbleHash) {
    }

    private static final int CELL = 72;
    private static final int GAP = 6;

    private final Screen parent;
    private final List<Option> options = new ArrayList<>();
    private int gridX;
    private int gridY;
    private int cols;

    public BubbleScreen(Screen parent) {
        super(Component.translatable("screen.meme.bubble.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        options.clear();
        options.add(new Option("",
                Component.translatable("screen.meme.bubble.default").getString(), null)); // no override
        options.add(new Option(EmoteProto.BUBBLE_NONE,
                Component.translatable("screen.meme.bubble.none").getString(), null));    // draw nothing
        for (ClientEmotes.BubbleOption b : ClientEmotes.bubbles()) {
            options.add(new Option(b.hash(), stripPng(b.name()), b.hash()));
        }

        int top = 40;
        int avail = this.width - 20;
        cols = Math.max(1, (avail + GAP) / (CELL + GAP));
        int rows = (options.size() + cols - 1) / cols;
        int gridW = cols * CELL + (cols - 1) * GAP;
        gridX = (this.width - gridW) / 2;
        gridY = top;

        int bottom = Math.max(top + rows * (CELL + GAP) + 8, this.height - 34);
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose())
                .bounds(this.width / 2 - 75, Math.min(bottom, this.height - 28), 150, 20).build());
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        int hit = cellAt(event.x(), event.y());
        if (hit >= 0) {
            String choice = options.get(hit).choice();
            ClientPrefs.setBubble(choice);
            if (ClientEmotes.connected()) {
                ClientNet.setBubble(choice);
            }
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    private int cellAt(double mx, double my) {
        for (int i = 0; i < options.size(); i++) {
            int x = gridX + (i % cols) * (CELL + GAP);
            int y = gridY + (i / cols) * (CELL + GAP);
            if (mx >= x && mx < x + CELL && my >= y && my < y + CELL) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);
        if (!ClientEmotes.bubbleChoiceEnabled()) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("screen.meme.bubble.disabled").getString(),
                    this.width / 2, gridY + 8, 0xFFFF8888);
            return;
        }
        String current = ClientPrefs.bubble();
        for (int i = 0; i < options.size(); i++) {
            Option opt = options.get(i);
            int x = gridX + (i % cols) * (CELL + GAP);
            int y = gridY + (i / cols) * (CELL + GAP);
            boolean selected = current.equals(opt.choice());
            boolean hover = mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL;
            graphics.fill(x, y, x + CELL, y + CELL, selected ? 0xFF3A6EA5 : (hover ? 0x66FFFFFF : 0x44000000));
            graphics.renderOutline(x, y, CELL, CELL, selected ? 0xFF8FD0FF : 0x66FFFFFF);
            drawPreview(graphics, opt, x, y);
            graphics.drawCenteredString(this.font, this.font.plainSubstrByWidth(opt.label(), CELL - 4),
                    x + CELL / 2, y + CELL - 11, 0xFFFFFFFF);
        }
    }

    /** The preview inside a cell: the bubble texture, or centered text for the two special cells. */
    private void drawPreview(GuiGraphics graphics, Option opt, int x, int y) {
        int area = CELL - 16; // leave room for the name strip at the bottom
        if (opt.bubbleHash() == null) {
            graphics.drawCenteredString(this.font, opt.choice().isEmpty() ? "◎" : "∅",
                    x + CELL / 2, y + area / 2 - 4, 0xFFCCCCCC);
            return;
        }
        EmoteTextures.Bubble bubble = EmoteTextures.bubble(opt.bubbleHash());
        if (bubble == null) {
            graphics.drawCenteredString(this.font, AssetCache.has(opt.bubbleHash()) ? "!" : "…",
                    x + CELL / 2, y + area / 2 - 4, 0xFFAAAAAA);
            return;
        }
        double fit = Math.min((double) area / bubble.width(), (double) area / bubble.height());
        int dw = Math.max(1, (int) (bubble.width() * fit));
        int dh = Math.max(1, (int) (bubble.height() * fit));
        graphics.blit(RenderPipelines.GUI_TEXTURED, bubble.texture(),
                x + (CELL - dw) / 2, y + 4 + (area - dh) / 2,
                0.0f, 0.0f, dw, dh, bubble.width(), bubble.height(), bubble.width(), bubble.height());
    }

    private static String stripPng(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (String ext : new String[] {".png", ".jpg", ".jpeg"}) {
            if (lower.endsWith(ext)) {
                return name.substring(0, name.length() - ext.length());
            }
        }
        return name;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
