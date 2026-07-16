package io.github.sarusm.meme.client.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The raw byte payload on the {@code meme:main} plugin-message channel — the mod's side of the
 * Paper-plugin protocol ({@link io.github.sarusm.meme.common.EmoteProto}). The body is opaque here (opcode +
 * DataOutputStream fields); {@link ClientNet} does the framing so the byte layout stays identical to what the
 * plugin's {@code DataInputStream} produces/consumes.
 */
public record EmotesPayload(byte[] data) implements CustomPacketPayload {
    public static final Type<EmotesPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("meme", "main"));

    public static final StreamCodec<ByteBuf, EmotesPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeBytes(payload.data()),
            buf -> {
                byte[] data = new byte[buf.readableBytes()];
                buf.readBytes(data);
                return new EmotesPayload(data);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
