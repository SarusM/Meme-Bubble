package io.github.sarusm.meme.mixin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import io.github.sarusm.meme.client.EmoteSounds;

import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.LoopingAudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;

/**
 * Serves {@code meme:sounds/<sha1>.ogg} locations from the downloaded-asset disk cache instead of the
 * resource manager — the whole reason emote sounds need no resource pack. Only our namespace is touched;
 * every other lookup falls through to vanilla untouched.
 */
@Mixin(SoundBufferLibrary.class)
public class SoundBufferLibraryMixin {
    @Inject(method = "getStream", at = @At("HEAD"), cancellable = true)
    private void meme$getStream(Identifier id, boolean looping,
            CallbackInfoReturnable<CompletableFuture<AudioStream>> cir) {
        Path file = EmoteSounds.resolveVirtual(id);
        if (file == null) {
            return;
        }
        cir.setReturnValue(CompletableFuture.supplyAsync(() -> {
            try {
                InputStream in = Files.newInputStream(file);
                // JOrbisAudioStream IS an AudioStream in 1.21.11 (FloatSampleSource -> FiniteAudioStream).
                return looping ? new LoopingAudioStream(JOrbisAudioStream::new, in)
                        : new JOrbisAudioStream(in);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }));
    }
}
