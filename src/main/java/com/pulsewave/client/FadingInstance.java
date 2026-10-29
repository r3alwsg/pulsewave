package com.pulsewave.client;

import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.random.Random;

/**
 * A music track whose volume = fade * duck * gain.
 *  - fade: this track fading in/out (crossfades, end-of-track)
 *  - duck: channel-wide ducking (base music under a combat overlay)
 */
final class FadingInstance extends MovingSoundInstance {
    private float fade = 0f, fadeTarget = 1f, fadeStep = 0.01f;
    private float duck, duckTarget, duckStep = 0.02f;
    private float gain;
    private int age;
    private int endFadeAt = -1, endFadeTicks;

    FadingInstance(SoundEvent event, float gain, float startDuck) {
        super(event, SoundCategory.MUSIC, Random.create());
        this.attenuationType = AttenuationType.NONE;
        this.relative = true;
        this.repeat = false;
        this.gain = gain;
        this.duck = this.duckTarget = startDuck;
        this.volume = 0.0001f;
    }

    /** Vanilla skips sounds whose volume is 0 at start; we always start faded in from 0. */
    @Override
    public boolean shouldAlwaysPlay() { return true; }

    void setGain(float gain) { this.gain = gain; }

    /** The volume this track is playing at right now (fade x duck x gain). */
    float volumeNow() { return fade * duck * gain; }

    void fadeIn(int ticks) {
        fadeTarget = 1f;
        fadeStep = 1f / Math.max(1, ticks);
        if (ticks <= 0) fade = 1f;
    }

    void fadeOut(int ticks) {
        fadeTarget = 0f;
        fadeStep = Math.max(fade, 0.01f) / Math.max(1, ticks);
    }

    void setDuck(float target, int ticks) {
        duckTarget = target;
        duckStep = Math.abs(1f - target) / Math.max(1, ticks);
    }

    /** Schedule an automatic fade-out so the track ends gracefully. */
    void scheduleEndFade(int startAtAge, int fadeTicks) {
        endFadeAt = startAtAge;
        endFadeTicks = fadeTicks;
    }

    @Override
    public void tick() {
        age++;
        if (endFadeAt >= 0 && age >= endFadeAt && fadeTarget > 0f) fadeOut(endFadeTicks);
        fade = approach(fade, fadeTarget, fadeStep);
        duck = approach(duck, duckTarget, duckStep);
        this.volume = fade * duck * gain;
        if (fadeTarget <= 0f && fade <= 0f) setDone();
    }

    private static float approach(float cur, float target, float step) {
        if (cur < target) return Math.min(target, cur + step);
        return Math.max(target, cur - step);
    }
}
