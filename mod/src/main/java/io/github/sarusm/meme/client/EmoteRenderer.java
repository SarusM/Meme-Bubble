package io.github.sarusm.meme.client;

import java.util.Map;
import java.util.UUID;

import io.github.sarusm.meme.common.EmoteDef;
import io.github.sarusm.meme.common.EmoteProto;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import org.joml.Matrix4f;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the active emotes ({@link ClientEmotes}) as camera-facing speech-bubble billboards over the emitting
 * players, hooked on Fabric's {@code WorldRenderEvents.END_MAIN} — AFTER translucent terrain, so water and
 * stained glass can never blend over (tint) the bubble. Everything is drawn nameplate-style in two passes:
 * a see-through "ghost" pass ({@link #GHOST_ALPHA}), then a depth-tested full-alpha pass on top wherever
 * the emote is in direct view (begin order = flush order in the buffer source, so the crisp pass overdraws
 * the ghost). The ghost pass is gated by a camera→bubble raycast ({@code ClipContext.Block.VISUAL} +
 * {@code Fluid.NONE}: glass has an empty visual shape, fluids are skipped), so the emote shows through
 * water and glass but NOT through visually solid walls.
 * The proven ClashBowlerMod billboard: the quad plane faces the camera POSITION (forward =
 * camPos − emotePos), kept upright with world-up; bubble placement (anchor/offsets/scale) and the GIF seat
 * (contain-fit × gifScale, bottom-center + pixel offsets) come from the server's per-emote {@link EmoteDef}.
 * Viewers' own mutes ({@link ClientPrefs}) hide bubbles entirely.
 */
public final class EmoteRenderer {
    /** Emotes farther than this from the camera aren't drawn (they'd be sub-pixel anyway). */
    private static final double MAX_DISTANCE = 128.0;
    /** Skip when the camera is basically inside the bubble (degenerate billboard basis). */
    private static final double MIN_DISTANCE = 0.3;
    /** One "loop" of an emote with no animation and no (parseable) sound to take the length from. */
    private static final long STATIC_CYCLE_MS = 3000;
    /** Alpha of the see-through pass — how strongly an emote shows through water/glass/walls (~70%). */
    private static final int GHOST_ALPHA = 178;

    private EmoteRenderer() {
    }

    /** Hook the world renderer (called from the client initializer). */
    public static void register() {
        WorldRenderEvents.END_MAIN.register(EmoteRenderer::render);
    }

    private static void render(WorldRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || ClientEmotes.active().isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();

        Vec3 camPos = ctx.worldState().cameraRenderState.pos;
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);

        var it = ClientEmotes.active().entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ClientEmotes.Active> entry = it.next();
            ClientEmotes.Active active = entry.getValue();
            EmoteDef def = active.resolveDef(); // catalogue def, or the inline one for player-pack emotes
            if (def == null) {
                it.remove(); // catalogue changed under us
                continue;
            }
            EmoteTextures.Gif gif = EmoteTextures.gif(def.gifHash);
            // Loop-limited lifetime; the cycle length appears once the deciding asset is downloaded.
            long cycleMs = cycleMs(def, gif);
            if (def.loops > 0 && cycleMs > 0
                    && now - active.startMillis() >= (long) (def.loops * cycleMs)) {
                it.remove();
                EmoteSounds.stop(entry.getKey());
                continue;
            }
            if (ClientPrefs.isHidden(entry.getKey())) {
                continue; // stays active (unmuting mid-emote brings it back), just not drawn
            }
            AbstractClientPlayer player = null;
            for (AbstractClientPlayer p : mc.level.players()) {
                if (p.getUUID().equals(entry.getKey())) {
                    player = p;
                    break;
                }
            }
            if (player == null || player.isRemoved()) {
                continue; // out of tracking range; resumes if they come back before the emote expires
            }
            renderEmote(ctx, player, def, active.bubbleOverride(), gif, camPos, partialTick,
                    now - active.startMillis());
        }
    }

    /**
     * One emote "loop" in ms: the GIF cycle for animated emotes; otherwise (static image / sound-only)
     * the sound's play length, falling back to {@link #STATIC_CYCLE_MS}. -1 = the deciding asset is
     * still downloading, no expiry yet (mirrors the old "no cycle until the GIF is decoded" rule).
     */
    private static long cycleMs(EmoteDef def, EmoteTextures.Gif gif) {
        if (gif != null && gif.animated()) {
            return gif.totalMs();
        }
        if (!def.soundHash.isEmpty()) {
            long soundMs = EmoteSounds.durationMs(def.soundHash);
            if (soundMs > 0) {
                return soundMs;
            }
            if (soundMs < 0) {
                return -1; // sound still downloading — its length is the emote's length
            }
            // 0 = unparseable file: fall through to the fixed default
        }
        if (gif != null || def.gifHash.isEmpty()) {
            return STATIC_CYCLE_MS;
        }
        return -1; // image still downloading
    }

    private static void renderEmote(WorldRenderContext ctx, AbstractClientPlayer player, EmoteDef def,
            String bubbleOverride, EmoteTextures.Gif gif, Vec3 camPos, float partialTick, long elapsedMs) {
        // The emitter's chosen bubble (server emotes) overrides the emote's own: "none" = no bubble,
        // a hash = that bubble, "" / null = leave the emote's bubble. (Local emotes bake it into the def.)
        String bubbleHash = def.bubbleHash;
        if (bubbleOverride != null && !bubbleOverride.isEmpty()) {
            bubbleHash = bubbleOverride.equals(EmoteProto.BUBBLE_NONE) ? "" : bubbleOverride;
        }
        EmoteTextures.Bubble bubble = EmoteTextures.bubble(bubbleHash);
        // Sound-only emotes have no image by design — their visual is the name text (drawn below).
        boolean soundOnly = def.gifHash.isEmpty();
        if (bubble == null && gif == null && !soundOnly) {
            return; // both assets still downloading / broken
        }

        Vec3 base = player.getPosition(partialTick).add(0.0, player.getBbHeight() * def.anchor, 0.0);

        // FacingCamera basis: forward points AT the camera position; right/up keep the bubble upright.
        Vec3 toCam = camPos.subtract(base);
        double dist = toCam.length();
        if (dist < MIN_DISTANCE || dist > MAX_DISTANCE) {
            return;
        }
        Vec3 f = toCam.scale(1.0 / dist);
        Vec3 r = new Vec3(0.0, 1.0, 0.0).cross(f);
        if (r.lengthSqr() < 1.0e-6) {
            // Looking straight down/up at it: derive "right" from the camera's own orientation instead.
            org.joml.Vector3f camRight = new org.joml.Vector3f(1.0f, 0.0f, 0.0f)
                    .rotate(ctx.worldState().cameraRenderState.orientation);
            r = new Vec3(camRight.x(), camRight.y(), camRight.z());
        }
        r = r.normalize();
        Vec3 u = f.cross(r);

        // The bubble's bottom-center point, with the configured offsets applied in the billboard frame.
        Vec3 point = base.add(r.scale(def.offsetX)).add(u.scale(def.offsetY)).add(f.scale(def.offsetZ));

        // Bubble rectangle in world units: height = scale, width from the PNG's aspect.
        double bubbleH = def.scale;
        double bubbleW = bubble != null
                ? bubbleH * (double) bubble.width() / bubble.height()
                : bubbleH * (gif != null ? (double) gif.width / gif.height : 1.0);

        // Solid walls hide the emote entirely: the ghost pass runs only if nothing VISUALLY solid sits on
        // the camera→bubble line. Glass (incl. stained) has an empty visual shape and fluids are skipped,
        // so behind water/glass the ghost still shows; the crisp pass self-hides via the depth test.
        Vec3 bubbleCenter = point.add(u.scale(bubbleH * 0.5));
        boolean ghost = player.level().clip(new ClipContext(camPos, bubbleCenter,
                ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS;

        PoseStack poseStack = ctx.matrices();
        poseStack.pushPose();
        poseStack.translate(point.x - camPos.x, point.y - camPos.y, point.z - camPos.z);
        PoseStack.Pose pose = poseStack.last();
        MultiBufferSource buffers = ctx.consumers();

        if (bubble != null) {
            quad(pose, buffers, bubble.texture(), ghost, r, u, f, -bubbleW / 2.0, 0.0, bubbleW, bubbleH, 0.0);
        }

        if (gif != null) {
            // Seat the gif inside the bubble: contain-fit × gifScale, its bottom-center pinned at the
            // bubble's bottom-center plus the configured pixel offsets (y positive = down, texture-style).
            double gifAspect = (double) gif.width / gif.height;
            double fitH = Math.min(bubbleH, bubbleW / gifAspect);
            double gifH = fitH * def.gifScale;
            double gifW = gifH * gifAspect;
            double px2world = bubble != null ? bubbleW / bubble.width() : bubbleH / 512.0;
            double posX = 0.5 * bubbleW + def.gifOffsetX * px2world;   // from the bubble's left edge
            double posY = 1.0 * bubbleH + def.gifOffsetY * px2world;   // from the bubble's TOP, y down
            double topLeftX = posX - 0.5 * gifW;
            double topLeftY = posY - 1.0 * gifH;
            // Convert to the quad frame used by quad(): x from bubble center-left, y UP from the bottom.
            double x0 = -bubbleW / 2.0 + topLeftX;
            double y0 = bubbleH - topLeftY - gifH;
            // Lift a hair toward the camera so the gif never z-fights the bubble face.
            double lift = Math.max(0.004, bubbleH * 0.01);
            Identifier frame = gif.frameAt(elapsedMs);
            quad(pose, buffers, frame, ghost, r, u, f, x0, y0, gifW, gifH, lift);
        } else if (soundOnly) {
            // Sound-only visual: the emote's name where the gif would sit — inside the bubble, or on a
            // white plate when the emote has no bubble. gifScale/gif-offset keep tuning the seat.
            double lift = Math.max(0.004, bubbleH * 0.01);
            if (bubble != null) {
                double px2world = bubbleW / bubble.width();
                drawName(poseStack, buffers, ghost, r, u, f, def.name,
                        def.gifOffsetX * px2world, 0.5 * bubbleH - def.gifOffsetY * px2world,
                        bubbleH * 0.22 * def.gifScale, bubbleW * 0.85,
                        0xFF202020, 0, lift);
            } else {
                drawName(poseStack, buffers, ghost, r, u, f, def.name,
                        0.0, 0.5 * bubbleH,
                        bubbleH * 0.25 * def.gifScale, bubbleH * 3.0,
                        0xFF101010, 0xFFFFFFFF, lift);
            }
        }

        poseStack.popPose();
    }

    /**
     * {@code def.name} drawn onto the billboard plane — the visual of a sound-only emote. The text is
     * centered at ({@code centerX},{@code centerY}) in the billboard frame (x along {@code r} from the
     * anchor, y along {@code u} up), {@code textH} blocks tall, shrunk to fit {@code maxW}. A non-zero
     * {@code background} draws the font's backing plate behind the glyphs (the "white plate" case).
     */
    private static void drawName(PoseStack poseStack, MultiBufferSource buffers, boolean ghost,
            Vec3 r, Vec3 u, Vec3 f, String text, double centerX, double centerY, double textH, double maxW,
            int color, int background, double lift) {
        Font font = Minecraft.getInstance().font;
        int w = Math.max(1, font.width(text));
        double s = textH / 9.0; // world units per font pixel (line height 9)
        if (w * s > maxW) {
            s = maxW / w;
        }
        poseStack.pushPose();
        poseStack.translate(f.x * lift, f.y * lift, f.z * lift);
        // Map font pixel space (x right, y DOWN) onto the billboard plane: x -> r, y -> -u, z -> f.
        poseStack.mulPose(new Matrix4f(
                (float) r.x, (float) r.y, (float) r.z, 0f,
                (float) -u.x, (float) -u.y, (float) -u.z, 0f,
                (float) f.x, (float) f.y, (float) f.z, 0f,
                0f, 0f, 0f, 1f));
        poseStack.scale((float) s, (float) s, (float) s);
        float textX = (float) (centerX / s) - w / 2.0f;
        float textY = (float) (-centerY / s) - 4.5f;
        // Ghost pass first, crisp depth-tested pass on top — same scheme as quad().
        if (ghost) {
            font.drawInBatch(text, textX, textY, ghost(color), false, poseStack.last().pose(), buffers,
                    Font.DisplayMode.SEE_THROUGH, ghost(background), LightTexture.FULL_BRIGHT);
        }
        font.drawInBatch(text, textX, textY, color, false, poseStack.last().pose(), buffers,
                Font.DisplayMode.NORMAL, background, LightTexture.FULL_BRIGHT);
        poseStack.popPose();
    }

    /** {@code argb} at {@link #GHOST_ALPHA} (scaled by its own alpha); 0 stays 0 (= "no background"). */
    private static int ghost(int argb) {
        if (argb == 0) {
            return 0;
        }
        int a = (argb >>> 24) * GHOST_ALPHA / 255;
        return (a << 24) | (argb & 0xFFFFFF);
    }

    /**
     * One camera-facing textured quad in the billboard frame: {@code x0,y0} = bottom-left corner (x along
     * {@code r} from the anchor point, y along {@code u} up from it), lifted {@code lift} toward the camera.
     * Counter-clockwise as seen from the camera, full texture. Drawn twice: ghost pass (only when the
     * raycast said the emote isn't walled off), then crisp pass (see the class doc). The {@code text}
     * render types carry no normals, so the shader applies no diffuse — brightness stays constant at
     * FULL_BRIGHT whatever the viewing angle/height.
     */
    private static void quad(PoseStack.Pose pose, MultiBufferSource buffers, Identifier texture, boolean ghost,
            Vec3 r, Vec3 u, Vec3 f, double x0, double y0, double w, double h, double lift) {
        if (ghost) {
            emit(pose, buffers.getBuffer(RenderTypes.textSeeThrough(texture)), r, u, f, x0, y0, w, h, lift, GHOST_ALPHA);
        }
        emit(pose, buffers.getBuffer(RenderTypes.text(texture)), r, u, f, x0, y0, w, h, lift, 255);
    }

    private static void emit(PoseStack.Pose pose, VertexConsumer vc, Vec3 r, Vec3 u, Vec3 f,
            double x0, double y0, double w, double h, double lift, int alpha) {
        Vec3 liftVec = f.scale(lift);
        vertex(pose, vc, r.scale(x0).add(u.scale(y0)).add(liftVec), 0.0f, 1.0f, alpha);          // bottom-left
        vertex(pose, vc, r.scale(x0 + w).add(u.scale(y0)).add(liftVec), 1.0f, 1.0f, alpha);      // bottom-right
        vertex(pose, vc, r.scale(x0 + w).add(u.scale(y0 + h)).add(liftVec), 1.0f, 0.0f, alpha);  // top-right
        vertex(pose, vc, r.scale(x0).add(u.scale(y0 + h)).add(liftVec), 0.0f, 0.0f, alpha);      // top-left
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer vc, Vec3 p, float uTex, float vTex, int alpha) {
        vc.addVertex(pose, (float) p.x, (float) p.y, (float) p.z)
                .setColor(255, 255, 255, alpha)
                .setUv(uTex, vTex)
                .setLight(LightTexture.FULL_BRIGHT);
    }
}
