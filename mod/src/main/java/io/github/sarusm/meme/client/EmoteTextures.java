package io.github.sarusm.meme.client;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;

import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Client GPU cache for downloaded emote assets, keyed by content hash (see {@link AssetCache}): speech-bubble
 * PNGs and emote images — animated GIFs or static PNG/JPGs (decoded as a one-frame {@link Gif}, sniffed by
 * magic bytes since cache files carry no extension). Loads lazily on first render (GPU device guaranteed up);
 * a broken file logs once and never crashes the frame. Content-hash keys mean entries never go stale, so
 * nothing is ever evicted within a session.
 *
 * <p>GIF decoding is the proven ClashBowlerMod pipeline: JDK ImageIO reader, frames composited honouring the
 * GIF disposal method (none / restoreToBackgroundColor / restoreToPrevious), downscaled to
 * {@link #MAX_TEXTURE} px on the longest side, one {@link DynamicTexture} per frame; delays of ≤1 cs play at
 * 100 ms (the de-facto browser rule). Animation is wall-clock driven.
 */
public final class EmoteTextures {
    /** A loaded speech-bubble image (PNG or JPG). */
    public record Bubble(Identifier texture, int width, int height) {
    }

    /** A loaded, decoded GIF: one registered texture per composited frame + cumulative delays. */
    public static final class Gif {
        private final Identifier[] frames;
        private final int[] endMs;  // cumulative delay: frame i shows while t < endMs[i]
        private final int totalMs;
        public final int width;
        public final int height;

        Gif(Identifier[] frames, int[] delaysMs, int width, int height) {
            this.frames = frames;
            this.endMs = new int[delaysMs.length];
            int acc = 0;
            for (int i = 0; i < delaysMs.length; i++) {
                acc += delaysMs[i];
                this.endMs[i] = acc;
            }
            this.totalMs = Math.max(1, acc);
            this.width = width;
            this.height = height;
        }

        /** Length of one full playback cycle in milliseconds (the loop-based emote duration unit). */
        public int totalMs() {
            return totalMs;
        }

        /** False for a single frame (static PNG/JPG or a one-frame gif) — no meaningful cycle length. */
        public boolean animated() {
            return frames.length > 1;
        }

        /** The frame to show {@code elapsedMs} after the emote started (loops forever). */
        public Identifier frameAt(long elapsedMs) {
            int t = (int) (Math.max(0, elapsedMs) % totalMs);
            for (int i = 0; i < endMs.length; i++) {
                if (t < endMs[i]) {
                    return frames[i];
                }
            }
            return frames[frames.length - 1];
        }
    }

    /** Longest texture side after downscale — a big GIF would otherwise upload hundreds of MB. */
    private static final int MAX_TEXTURE = 512;
    /** Runaway guard: GIFs beyond this many frames are truncated (each frame is its own GPU texture). */
    private static final int MAX_FRAMES = 256;

    private static final Map<String, Bubble> BUBBLES = new HashMap<>();
    private static final Map<String, Gif> GIFS = new HashMap<>();
    /** Hashes that failed to decode — remembered so a broken file logs once instead of every frame. */
    private static final Set<String> FAILED = new HashSet<>();

    private EmoteTextures() {
    }

    /** The bubble image by content hash, loading it on first use; null while not downloaded / broken.
     *  Decoded via ImageIO (not {@link NativeImage#read}) so .jpg bubbles work alongside .png. */
    public static Bubble bubble(String hash) {
        if (hash.isEmpty() || FAILED.contains(hash)) {
            return null;
        }
        Bubble cached = BUBBLES.get(hash);
        if (cached != null) {
            return cached;
        }
        if (!AssetCache.has(hash)) {
            return null; // still downloading
        }
        try (InputStream in = Files.newInputStream(AssetCache.path(hash))) {
            BufferedImage decoded = ImageIO.read(in);
            if (decoded == null) {
                throw new IOException("unsupported image format");
            }
            NativeImage image = toNativeImage(decoded);
            Identifier id = register("emotes/bubble/" + hash,
                    new DynamicTexture(() -> "meme bubble " + hash, image));
            Bubble bubble = new Bubble(id, image.getWidth(), image.getHeight());
            BUBBLES.put(hash, bubble);
            return bubble;
        } catch (IOException e) {
            fail(hash, "Failed to load speech bubble " + hash, e);
            return null;
        }
    }

    /** The decoded GIF by content hash, loading it on first use; null while not downloaded / broken. */
    public static Gif gif(String hash) {
        if (hash.isEmpty() || FAILED.contains(hash)) {
            return null;
        }
        Gif cached = GIFS.get(hash);
        if (cached != null) {
            return cached;
        }
        if (!AssetCache.has(hash)) {
            return null; // still downloading
        }
        try {
            Gif gif = loadImage(hash, AssetCache.path(hash));
            GIFS.put(hash, gif);
            return gif;
        } catch (Exception e) {
            fail(hash, "Failed to decode emote image " + hash, e);
            return null;
        }
    }

    /** Dispatch by content (files are cached by hash, extension unknown): GIF or a static PNG/JPG. */
    private static Gif loadImage(String hash, Path file) throws IOException {
        byte[] magic = new byte[3];
        try (InputStream in = Files.newInputStream(file)) {
            if (in.read(magic) < 3) {
                throw new IOException("image file too short");
            }
        }
        if (magic[0] == 'G' && magic[1] == 'I' && magic[2] == 'F') {
            return loadGif(hash, file);
        }
        return loadStill(hash, file);
    }

    /** A static PNG/JPG as a one-frame Gif — the whole render/thumbnail pipeline stays unchanged. */
    private static Gif loadStill(String hash, Path file) throws IOException {
        BufferedImage img;
        try (InputStream in = Files.newInputStream(file)) {
            img = ImageIO.read(in);
        }
        if (img == null) {
            throw new IOException("unsupported image format");
        }
        double scale = Math.min(1.0, (double) MAX_TEXTURE / Math.max(img.getWidth(), img.getHeight()));
        int outW = Math.max(1, (int) Math.round(img.getWidth() * scale));
        int outH = Math.max(1, (int) Math.round(img.getHeight() * scale));
        BufferedImage frame = scaled(img, outW, outH); // also converts any color model to ARGB
        Identifier id = register("emotes/gif/" + hash + "/0", new DynamicTexture(
                () -> "meme still " + hash, toNativeImage(frame)));
        MemeClient.LOGGER.info("Decoded emote image {} ({}x{} -> {}x{})",
                hash, img.getWidth(), img.getHeight(), outW, outH);
        return new Gif(new Identifier[] {id}, new int[] {100}, outW, outH);
    }

    // ---------------------------------------------------------------------------------------------------------
    // GIF decoding
    // ---------------------------------------------------------------------------------------------------------

    /** One raw ImageIO frame before compositing: pixels + placement on the logical screen + timing/disposal. */
    private record RawFrame(BufferedImage image, int x, int y, int delayMs, String disposal) {
    }

    private static Gif loadGif(String hash, Path file) throws IOException {
        ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
        List<RawFrame> raws = new ArrayList<>();
        int screenW = 1;
        int screenH = 1;
        try (InputStream stream = Files.newInputStream(file);
                ImageInputStream in = ImageIO.createImageInputStream(stream)) {
            reader.setInput(in, false, false);
            int count = reader.getNumImages(true);
            if (count > MAX_FRAMES) {
                MemeClient.LOGGER.warn("Emote gif {} has {} frames; truncating to {}",
                        hash, count, MAX_FRAMES);
                count = MAX_FRAMES;
            }
            for (int i = 0; i < count; i++) {
                BufferedImage img = reader.read(i);
                int x = 0;
                int y = 0;
                int delayCs = 0;
                String disposal = "none";
                IIOMetadataNode root =
                        (IIOMetadataNode) reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
                NodeList children = root.getChildNodes();
                for (int c = 0; c < children.getLength(); c++) {
                    Node node = children.item(c);
                    if ("ImageDescriptor".equals(node.getNodeName())) {
                        x = intAttr(node, "imageLeftPosition", 0);
                        y = intAttr(node, "imageTopPosition", 0);
                    } else if ("GraphicControlExtension".equals(node.getNodeName())) {
                        delayCs = intAttr(node, "delayTime", 0);
                        Node d = node.getAttributes().getNamedItem("disposalMethod");
                        if (d != null) {
                            disposal = d.getNodeValue();
                        }
                    }
                }
                // De-facto standard (browsers): a 0/1-centisecond delay plays at 100 ms.
                int delayMs = delayCs <= 1 ? 100 : delayCs * 10;
                raws.add(new RawFrame(img, x, y, delayMs, disposal));
                screenW = Math.max(screenW, x + img.getWidth());
                screenH = Math.max(screenH, y + img.getHeight());
            }
        } finally {
            reader.dispose();
        }
        if (raws.isEmpty()) {
            throw new IOException("gif has no frames");
        }

        double scale = Math.min(1.0, (double) MAX_TEXTURE / Math.max(screenW, screenH));
        int outW = Math.max(1, (int) Math.round(screenW * scale));
        int outH = Math.max(1, (int) Math.round(screenH * scale));

        // Composite frame-by-frame honouring the disposal method, snapshotting (and downscaling) each draw.
        BufferedImage canvas = new BufferedImage(screenW, screenH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        Identifier[] frames = new Identifier[raws.size()];
        int[] delays = new int[raws.size()];
        String texBase = "emotes/gif/" + hash + "/";
        try {
            BufferedImage previous = null;
            for (int i = 0; i < raws.size(); i++) {
                RawFrame raw = raws.get(i);
                if ("restoreToPrevious".equals(raw.disposal())) {
                    previous = deepCopy(canvas);
                }
                g.drawImage(raw.image(), raw.x(), raw.y(), null);

                BufferedImage snapshot = scaled(canvas, outW, outH);
                final int frameIndex = i;
                frames[i] = register(texBase + i, new DynamicTexture(
                        () -> "meme gif " + hash + " #" + frameIndex, toNativeImage(snapshot)));
                delays[i] = raw.delayMs();

                switch (raw.disposal()) {
                    case "restoreToBackgroundColor" -> {
                        g.setComposite(AlphaComposite.Clear);
                        g.fillRect(raw.x(), raw.y(), raw.image().getWidth(), raw.image().getHeight());
                        g.setComposite(AlphaComposite.SrcOver);
                    }
                    case "restoreToPrevious" -> {
                        g.setComposite(AlphaComposite.Src);
                        g.drawImage(previous, 0, 0, null);
                        g.setComposite(AlphaComposite.SrcOver);
                    }
                    default -> {
                        // none / doNotDispose: the frame stays as the base for the next one
                    }
                }
            }
        } finally {
            g.dispose();
        }
        MemeClient.LOGGER.info("Decoded emote gif {} ({} frames, {}x{} -> {}x{})",
                hash, frames.length, screenW, screenH, outW, outH);
        return new Gif(frames, delays, outW, outH);
    }

    private static int intAttr(Node node, String attr, int def) {
        NamedNodeMap attrs = node.getAttributes();
        Node a = attrs == null ? null : attrs.getNamedItem(attr);
        if (a == null) {
            return def;
        }
        try {
            return Integer.parseInt(a.getNodeValue());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static BufferedImage deepCopy(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return copy;
    }

    /** A new (always deep) copy scaled to {@code w}×{@code h} — also serves as the per-frame snapshot copy. */
    private static BufferedImage scaled(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setComposite(AlphaComposite.Src);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** ARGB BufferedImage -> RGBA NativeImage (setPixelABGR takes the little-endian RGBA byte order). */
    private static NativeImage toNativeImage(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        NativeImage out = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int p = argb[row + x];
                int abgr = (p & 0xFF000000)            // A
                        | ((p & 0x00FF0000) >>> 16)    // R -> low byte
                        | (p & 0x0000FF00)             // G stays
                        | ((p & 0x000000FF) << 16);    // B -> third byte
                out.setPixelABGR(x, y, abgr);
            }
        }
        return out;
    }

    private static Identifier register(String path, DynamicTexture texture) {
        Identifier id = Identifier.fromNamespaceAndPath("meme", path);
        Minecraft.getInstance().getTextureManager().register(id, texture);
        return id;
    }

    private static void fail(String hash, String message, Exception e) {
        FAILED.add(hash);
        MemeClient.LOGGER.error(message, e);
    }
}
