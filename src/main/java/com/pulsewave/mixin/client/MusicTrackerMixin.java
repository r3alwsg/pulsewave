package com.pulsewave.mixin.client;

import com.pulsewave.PulsewaveConfig;
import net.minecraft.client.sound.MusicTracker;
import net.minecraft.sound.MusicSound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Silences vanilla music (menu, game, credits, creative...) so Pulsewave is the only source. */
@Mixin(MusicTracker.class)
public class MusicTrackerMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void pulsewave$tick(CallbackInfo ci) {
        if (suppress()) ci.cancel();
    }

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void pulsewave$play(MusicSound type, CallbackInfo ci) {
        if (suppress()) ci.cancel();
    }

    private static boolean suppress() {
        PulsewaveConfig c = PulsewaveConfig.INSTANCE;
        return c.enabled && c.suppressVanillaMusic;
    }
}
