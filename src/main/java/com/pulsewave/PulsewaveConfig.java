package com.pulsewave;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** config/pulsewave.json. Server-side values apply on the server, the rest on each client. */
public final class PulsewaveConfig {
    public enum Mode { CROSSFADE, GAP, HYBRID }

    /** Where PVP / PVE combat is detected: AUTO = server if it has Pulsewave, else your own game. */
    public enum CombatDetection { AUTO, CLIENT, SERVER }

    public static PulsewaveConfig INSTANCE = new PulsewaveConfig();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ---- client ----
    public boolean enabled = true;
    public boolean suppressVanillaMusic = true;
    public float musicGain = 1.0f;

    /** CROSSFADE | GAP | HYBRID */
    public String transitionMode = "HYBRID";
    /** How long a new track takes to fade in. */
    public float fadeInSeconds = 3.0f;
    /** How long the old track takes to fade out. */
    public float fadeOutSeconds = 3.0f;
    /** Silence before the next music starts (after the old one has faded out). */
    public float gapSeconds = 2.0f;
    /** Show a "Now playing" popup (toast) whenever a track starts. */
    public boolean showNowPlaying = true;
    /** ...but never during combat / boss fights, so it can't spam you while PVPing. */
    public boolean hideNowPlayingInCombat = true;

    /** Show the timing sliders in milliseconds instead of seconds. */
    public boolean timingInMilliseconds = false;

    /** How long each situation stays active after it stops (seconds), by context id. Missing = default. */
    public Map<String, Float> holdSeconds = new LinkedHashMap<>();
    /** Per-boss detection range in blocks, by context id. Missing = bossRange. */
    public Map<String, Integer> bossRangeByBoss = new LinkedHashMap<>();

    /** Start the next track while the current one is still finishing, so songs flow into each other. */
    public boolean crossfadeAtEnd = true;
    /** How long that overlap lasts. */
    public float crossfadeSeconds = 4.0f;
    /** Per-folder fade time (seconds) by context id; missing = use the global fade times. */
    public Map<String, Float> folderFadeSeconds = new LinkedHashMap<>();

    /** How long a new base context must stay true before we switch (stops flapping at cave mouths, etc). */
    public float debounceSeconds = 2.0f;
    public float duckFadeSeconds = 1.5f;
    /** Base music volume while a combat/boss overlay plays. */
    public float combatDuckVolume = 0.05f;
    /** Fade-out length at the end of a track (length is read from the file). */
    public float endOfTrackFadeSeconds = 4.0f;
    /** AUTO | CLIENT | SERVER */
    public String combatDetection = "AUTO";

    /** On-screen readout of what Pulsewave is doing and why (also toggled with F6). */
    public boolean debugOverlay = false;

    /** Fade Pulsewave out while a jukebox / music disc is playing nearby. */
    public boolean jukeboxDucking = true;
    /** Pulsewave's volume (0..1) while a jukebox is playing nearby. */
    public float jukeboxDuckVolume = 0.0f;
    /** Per-folder volume (0..1) by context id; missing = 100%. */
    public Map<String, Float> folderVolume = new LinkedHashMap<>();
    /** Low Health music starts at or below this many hearts. */
    public int lowHealthHearts = 4;
    /** Swarm combat music starts when this many monsters target you. */
    public int intenseMobCount = 4;
    /** Base music is stopped after a boss fight has lasted this long (0 = never). */
    public int bossReleaseSeconds = 60;
    public int bossRange = 128;
    /** Optional override of the auto-detected track length (seconds) per context id. 0 = auto-detect. */
    public Map<String, Integer> trackLengthSeconds = new LinkedHashMap<>();

    // ---- server ----
    public int pveRange = 24;
    public float pveHoldSeconds = 8.0f;
    public float pvpTimeoutSeconds = 5.0f;
    public boolean pvpTeamProtection = true;

    public Mode mode() {
        try { return Mode.valueOf(transitionMode.trim().toUpperCase()); }
        catch (Exception e) { return Mode.HYBRID; }
    }

    public static float defaultHold(MusicContext c) {
        return switch (c) {
            case COMBAT_PVE -> 8f;
            case COMBAT_INTENSE -> 10f;
            case COMBAT_PVP -> 5f;
            case LOW_HEALTH -> 2f;
            case ANCIENT_CITY, TRIAL_CHAMBERS -> 3f;
            case BOSS_WITHER, BOSS_ENDER_DRAGON, BOSS_ELDER_GUARDIAN -> 3f;
            default -> 0f;
        };
    }

    public float holdFor(MusicContext c) {
        Float v = holdSeconds.get(c.id);
        return v != null ? Math.max(0f, v) : defaultHold(c);
    }

    public int bossRangeFor(MusicContext c) {
        Integer v = bossRangeByBoss.get(c.id);
        return v != null ? Math.max(8, v) : bossRange;
    }

    public float fadeInFor(MusicContext c) {
        Float v = folderFadeSeconds.get(c.id);
        return v != null ? v : fadeInSeconds;
    }

    public float fadeOutFor(MusicContext c) {
        Float v = folderFadeSeconds.get(c.id);
        return v != null ? v : fadeOutSeconds;
    }

    public float overlapFor(MusicContext c) {
        Float v = folderFadeSeconds.get(c.id);
        return v != null ? v : crossfadeSeconds;
    }

    public float folderVolume(MusicContext c) {
        Float v = folderVolume.get(c.id);
        return v == null ? 1f : Math.max(0f, Math.min(1f, v));
    }

    public CombatDetection combatMode() {
        try { return CombatDetection.valueOf(combatDetection.trim().toUpperCase()); }
        catch (Exception e) { return CombatDetection.AUTO; }
    }

    public int ticks(float seconds) { return Math.max(0, Math.round(seconds * 20f)); }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("pulsewave.json");
    }

    public static void load() {
        Path path = path();
        PulsewaveConfig cfg = null;
        if (Files.exists(path)) {
            try (Reader r = Files.newBufferedReader(path)) {
                cfg = GSON.fromJson(r, PulsewaveConfig.class);
            } catch (Exception e) {
                PulsewaveMod.LOGGER.error("Could not read pulsewave.json, using defaults", e);
            }
        }
        if (cfg == null) cfg = new PulsewaveConfig();
        if (cfg.folderVolume == null) cfg.folderVolume = new LinkedHashMap<>();
        if (cfg.folderFadeSeconds == null) cfg.folderFadeSeconds = new LinkedHashMap<>();
        if (cfg.holdSeconds == null) cfg.holdSeconds = new LinkedHashMap<>();
        if (cfg.bossRangeByBoss == null) cfg.bossRangeByBoss = new LinkedHashMap<>();
        if (cfg.trackLengthSeconds == null) cfg.trackLengthSeconds = new LinkedHashMap<>();
        for (MusicContext c : MusicContext.values()) cfg.trackLengthSeconds.putIfAbsent(c.id, 0);
        INSTANCE = cfg;
        save();
    }

    public static void save() {
        try (Writer w = Files.newBufferedWriter(path())) {
            GSON.toJson(INSTANCE, w);
        } catch (IOException e) {
            PulsewaveMod.LOGGER.warn("Could not write pulsewave.json", e);
        }
    }
}
