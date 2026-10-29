package com.pulsewave.client;

import com.pulsewave.MusicContext;
import com.pulsewave.PulsewaveConfig;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Path;

/** Plays one track from the manager so you can hear it; the normal music is muted meanwhile. */
final class PreviewPlayer {
    private PreviewPlayer() {}

    private static FadingInstance inst;
    private static MusicContext ctx;
    private static Path file;
    private static int age;

    static void play(MinecraftClient mc, MusicContext c, Path f) {
        stop(mc);
        PulsewaveConfig cfg = PulsewaveConfig.INSTANCE;
        UserMusic.queue(c, f);
        FadingInstance i = new FadingInstance(c.event(), cfg.musicGain * cfg.folderVolume(c), 1f);
        i.fadeIn(10);
        mc.getSoundManager().play(i);
        inst = i;
        ctx = c;
        file = f;
        age = 0;
    }

    static void stop(MinecraftClient mc) {
        if (inst != null && mc != null) mc.getSoundManager().stop(inst);
        inst = null;
        ctx = null;
        file = null;
    }

    static boolean isActive() { return inst != null; }
    static Path currentFile() { return file; }

    /** Called every client tick: follows the volume sliders and notices when the preview has ended. */
    static void tick(MinecraftClient mc) {
        if (inst == null) return;
        age++;
        PulsewaveConfig cfg = PulsewaveConfig.INSTANCE;
        inst.setGain(cfg.musicGain * cfg.folderVolume(ctx));
        if (inst.isDone() || (age > 40 && !mc.getSoundManager().isPlaying(inst))) {
            inst = null;
            ctx = null;
            file = null;
        }
    }
}
