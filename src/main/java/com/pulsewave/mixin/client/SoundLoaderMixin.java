package com.pulsewave.mixin.client;

import com.pulsewave.client.UserMusic;
import net.minecraft.client.sound.AudioStream;
import net.minecraft.client.sound.OggAudioStream;
import net.minecraft.client.sound.SoundLoader;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * sounds.json declares one placeholder per context (pulsewave:sounds/music/<ctx>.ogg).
 * When the game tries to stream that placeholder we hand it the file the engine picked
 * from .minecraft/pulsewave_music/<ctx>/ instead.
 */
@Mixin(SoundLoader.class)
public class SoundLoaderMixin {
    private static final String PREFIX = "sounds/music/";
    private static final String SUFFIX = ".ogg";

    @Inject(method = "loadStreamed", at = @At("HEAD"), cancellable = true)
    private void pulsewave$loadStreamed(Identifier id, boolean repeatInstantly,
                                   CallbackInfoReturnable<CompletableFuture<AudioStream>> cir) {
        if (!id.getNamespace().equals("pulsewave")) return;
        String path = id.getPath();
        if (!path.startsWith(PREFIX) || !path.endsWith(SUFFIX)) return;

        String ctxId = path.substring(PREFIX.length(), path.length() - SUFFIX.length());
        Path file = UserMusic.poll(ctxId);
        if (file == null) return; // nothing queued: fall through to normal (will fail quietly)

        cir.setReturnValue(CompletableFuture.supplyAsync(() -> {
            try {
                return (AudioStream) new OggAudioStream(new BufferedInputStream(Files.newInputStream(file)));
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, Util.getMainWorkerExecutor()));
    }
}
