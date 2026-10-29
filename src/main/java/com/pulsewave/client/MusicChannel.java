package com.pulsewave.client;

import com.pulsewave.PulsewaveConfig;
import com.pulsewave.MusicContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.SoundManager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * One "voice" of the engine. Plays at most one live track, plus any tracks still fading out.
 * Handles crossfade / gap / hybrid transitions, debouncing, track restarts and ducking.
 */
final class MusicChannel {
    private static final int START_GRACE_TICKS = 40;   // streaming sounds need a moment to start
    private static final int BROKEN_TICKS = 100;       // died younger than this => file missing / failed

    private static final class Track {
        final MusicContext ctx;
        final FadingInstance inst;
        final java.nio.file.Path file;
        final int lengthTicks;     // 0 = unknown
        int age;
        Track(MusicContext ctx, FadingInstance inst, java.nio.file.Path file, int lengthTicks) {
            this.ctx = ctx;
            this.inst = inst;
            this.file = file;
            this.lengthTicks = lengthTicks;
        }
    }

    private final boolean debounced;
    private final Consumer<MusicContext> onBroken;
    private final BiConsumer<MusicContext, java.nio.file.Path> onStart;

    private Track current;
    private final List<Track> dying = new ArrayList<>();
    private MusicContext pending;
    private boolean pendingFresh;   // true = entering this context, false = same context's next track
    private int gapLeft;
    private MusicContext candidate;
    private int candidateTicks;
    private float duck = 1f;

    MusicChannel(boolean debounced, Consumer<MusicContext> onBroken,
                 BiConsumer<MusicContext, java.nio.file.Path> onStart) {
        this.onStart = onStart;
        this.debounced = debounced;
        this.onBroken = onBroken;
    }

    void setDuck(float duck) { this.duck = duck; }

    /** One line for the debug overlay. */
    String describe() {
        String fading = dying.isEmpty() ? "" : "  (+" + dying.size() + " fading out)";
        if (current != null) {
            String name = current.file.getFileName().toString().replaceFirst("(?i)\\.ogg$", "");
            return current.ctx.id + " / " + name + "  " + Math.round(current.inst.volumeNow() * 100) + "%" + fading;
        }
        if (pending != null) {
            return "next: " + pending.id + " in " + String.format(java.util.Locale.ROOT, "%.1fs", Math.max(0, gapLeft) / 20f)
                    + (dying.isEmpty() ? "" : " (waiting for fade-out)");
        }
        return dying.isEmpty() ? "-" : "fading out";
    }

    MusicContext effective() {
        return pending != null ? pending : current != null ? current.ctx : null;
    }

    void update(MinecraftClient mc, PulsewaveConfig cfg, MusicContext desired) {
        SoundManager sm = mc.getSoundManager();

        if (current != null) current.age++;
        for (Track t : dying) t.age++;
        dying.removeIf(t -> gone(t, sm));

        int duckTicks = cfg.ticks(cfg.duckFadeSeconds);
        if (current != null) {
            current.inst.setDuck(duck, duckTicks);
            current.inst.setGain(cfg.musicGain * cfg.folderVolume(current.ctx));
        }
        for (Track t : dying) {
            t.inst.setDuck(duck, duckTicks);
            t.inst.setGain(cfg.musicGain * cfg.folderVolume(t.ctx));
        }

        // Track ended by itself (or never started).
        if (current != null && gone(current, sm)) {
            Track t = current;
            current = null;
            if (t.age < BROKEN_TICKS) {
                onBroken.accept(t.ctx);
                return;
            }
            if (desired == t.ctx) {
                pending = t.ctx;
                pendingFresh = false;
                gapLeft = cfg.mode() == PulsewaveConfig.Mode.CROSSFADE ? 0 : cfg.ticks(cfg.gapSeconds);
            }
        }

        // End-of-track crossfade: start the next track while this one is still finishing.
        if (cfg.crossfadeAtEnd && current != null && pending == null && desired == current.ctx && current.lengthTicks > 0) {
            int overlap = Math.min(cfg.ticks(cfg.overlapFor(current.ctx)), current.lengthTicks / 3);
            if (overlap > 0 && current.age >= current.lengthTicks - overlap) {
                MusicContext ctx = current.ctx;
                current.inst.fadeOut(overlap);
                dying.add(current);
                current = null;
                start(mc, cfg, ctx, false, overlap);
            }
        }

        // Debounce context changes.
        if (desired != candidate) { candidate = desired; candidateTicks = 0; } else candidateTicks++;
        MusicContext eff = effective();
        boolean accept = !debounced || eff == null || desired == null
                || candidateTicks >= cfg.ticks(cfg.debounceSeconds);
        if (accept && desired != eff) transitionTo(mc, cfg, desired);

        // Waiting out a gap: previous track must be silent, then the gap timer runs.
        if (pending != null && dying.isEmpty()) {
            if (--gapLeft <= 0) {
                MusicContext p = pending;
                pending = null;
                start(mc, cfg, p, pendingFresh);
            }
        }
    }

    private void transitionTo(MinecraftClient mc, PulsewaveConfig cfg, MusicContext target) {
        if (target == null) {
            fadeOutCurrent(cfg);
            pending = null;
            return;
        }
        if (current == null) {
            if (pending == null) gapLeft = dying.isEmpty() ? 0 : cfg.ticks(cfg.gapSeconds);
            pending = target;
            pendingFresh = true;
            return;
        }
        PulsewaveConfig.Mode mode = cfg.mode();
        boolean crossfade = mode == PulsewaveConfig.Mode.CROSSFADE
                || (mode == PulsewaveConfig.Mode.HYBRID && current.ctx.group.equals(target.group));
        fadeOutCurrent(cfg);
        if (crossfade) {
            pending = null;
            start(mc, cfg, target, true);
        } else {
            pending = target;
            pendingFresh = true;
            gapLeft = cfg.ticks(cfg.gapSeconds);
        }
    }

    private void fadeOutCurrent(PulsewaveConfig cfg) {
        if (current == null) return;
        current.inst.fadeOut(cfg.ticks(cfg.fadeOutFor(current.ctx)));
        dying.add(current);
        current = null;
    }

    private void start(MinecraftClient mc, PulsewaveConfig cfg, MusicContext ctx, boolean fresh) {
        start(mc, cfg, ctx, fresh, -1);
    }

    /** @param fadeInTicks fade-in length, or -1 to use this folder's / the global fade-in time */
    private void start(MinecraftClient mc, PulsewaveConfig cfg, MusicContext ctx, boolean fresh, int fadeInTicks) {
        java.nio.file.Path file = UserMusic.next(ctx, fresh);
        if (file == null) { onBroken.accept(ctx); return; }
        UserMusic.queue(ctx, file);

        FadingInstance inst = new FadingInstance(ctx.event(), cfg.musicGain * cfg.folderVolume(ctx), duck);
        inst.fadeIn(fadeInTicks >= 0 ? fadeInTicks : cfg.ticks(cfg.fadeInFor(ctx)));

        // Length is read from the file itself; the config value (if > 0) overrides it.
        int len = cfg.trackLengthSeconds.getOrDefault(ctx.id, 0);
        if (len <= 0) len = Math.round(OggInfo.seconds(file));
        int endFade = cfg.ticks(cfg.endOfTrackFadeSeconds);
        // With end-of-track crossfade on, the crossfade replaces the plain end fade-out.
        boolean crossfadeEnd = cfg.crossfadeAtEnd && len > 0;
        if (!crossfadeEnd && len > 0 && endFade > 0) {
            inst.scheduleEndFade(Math.max(1, len * 20 - endFade), endFade);
        }
        mc.getSoundManager().play(inst);
        current = new Track(ctx, inst, file, Math.max(0, len) * 20);
        onStart.accept(ctx, file);
    }

    private static boolean gone(Track t, SoundManager sm) {
        return t.inst.isDone() || (t.age > START_GRACE_TICKS && !sm.isPlaying(t.inst));
    }

    /** Hard stop everything (portals, world change, disabling the mod). */
    void reset(MinecraftClient mc) {
        SoundManager sm = mc.getSoundManager();
        if (current != null) sm.stop(current.inst);
        for (Track t : dying) sm.stop(t.inst);
        current = null;
        dying.clear();
        pending = null;
        candidate = null;
        candidateTicks = 0;
        duck = 1f;
    }
}
