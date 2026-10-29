package com.pulsewave.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.pulsewave.MusicContext;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * The player's music library: .minecraft/pulsewave_music/<context>/*.ogg
 * Per folder it remembers the track order, which tracks are switched off, and shuffle vs in-order
 * (saved in pulsewave_music/playlist.json).
 */
public final class UserMusic {
    private UserMusic() {}

    /** What gets saved per folder. */
    private static final class State {
        List<String> order = new ArrayList<>();
        Set<String> disabled = new LinkedHashSet<>();
        boolean shuffle = true;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Map<String, State> state = new TreeMap<>();

    private static final Map<MusicContext, List<Path>> FILES = new ConcurrentHashMap<>();
    private static final Map<String, Queue<Path>> QUEUED = new ConcurrentHashMap<>();
    private static final Map<MusicContext, Path> LAST = new ConcurrentHashMap<>();
    private static final Map<MusicContext, Integer> CURSOR = new ConcurrentHashMap<>();

    public static Path root() { return FabricLoader.getInstance().getGameDir().resolve("pulsewave_music"); }
    public static Path dir(MusicContext c) { return root().resolve(c.id); }
    private static Path stateFile() { return root().resolve("playlist.json"); }

    public static void init() {
        for (MusicContext c : MusicContext.values()) {
            try { Files.createDirectories(dir(c)); } catch (IOException ignored) { }
        }
        migrateLegacyFolders();
        loadState();
        rescanAll();
    }

    /** The old "ocean" and "underwater" folders were merged into "water": move their tracks over. */
    private static void migrateLegacyFolders() {
        Path water = root().resolve("water");
        for (String legacy : new String[]{"ocean", "underwater"}) {
            Path dir = root().resolve(legacy);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.list(dir)) {
                for (Path f : s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".ogg")).toList()) {
                    Files.createDirectories(water);
                    Path target = water.resolve(f.getFileName());
                    if (!Files.exists(target)) Files.move(f, target);
                }
            } catch (IOException ignored) { }
        }
    }

    // ---------------------------------------------------------------- saved state

    private static void loadState() {
        Path f = stateFile();
        if (!Files.exists(f)) return;
        try (Reader r = Files.newBufferedReader(f)) {
            Map<String, State> loaded = GSON.fromJson(r, new TypeToken<TreeMap<String, State>>() {}.getType());
            if (loaded != null) state = loaded;
        } catch (Exception ignored) { }
    }

    private static void saveState() {
        try (Writer w = Files.newBufferedWriter(stateFile())) {
            GSON.toJson(state, w);
        } catch (IOException ignored) { }
    }

    private static State stateOf(MusicContext c) {
        State s = state.computeIfAbsent(c.id, k -> new State());
        if (s.order == null) s.order = new ArrayList<>();
        if (s.disabled == null) s.disabled = new LinkedHashSet<>();
        return s;
    }

    // ---------------------------------------------------------------- scanning

    public static void rescanAll() {
        boolean changed = false;
        for (MusicContext c : MusicContext.values()) changed |= rescan(c, false);
        if (changed) saveState();
    }

    public static void rescan(MusicContext c) {
        if (rescan(c, false)) saveState();
    }

    /** @return true if the saved order / switches changed (so the caller should save). */
    private static boolean rescan(MusicContext c, boolean unused) {
        List<String> onDisk = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir(c))) {
            s.map(p -> p.getFileName().toString())
                    .filter(n -> n.toLowerCase().endsWith(".ogg"))
                    .sorted()
                    .forEach(onDisk::add);
        } catch (IOException ignored) { }

        State st = stateOf(c);
        List<String> merged = new ArrayList<>();
        for (String n : st.order) if (onDisk.contains(n) && !merged.contains(n)) merged.add(n);   // keep saved order
        for (String n : onDisk) if (!merged.contains(n)) merged.add(n);                           // new files go last

        boolean changed = !merged.equals(st.order);
        st.order = merged;
        changed |= st.disabled.retainAll(new HashSet<>(onDisk));

        List<Path> paths = new ArrayList<>();
        for (String n : merged) paths.add(dir(c).resolve(n));
        FILES.put(c, Collections.unmodifiableList(paths));
        return changed;
    }

    // ---------------------------------------------------------------- queries

    /** Every track in the folder, in playlist order (including switched-off ones). */
    public static List<Path> files(MusicContext c) { return FILES.getOrDefault(c, List.of()); }

    public static boolean isEnabled(MusicContext c, Path file) {
        return !stateOf(c).disabled.contains(file.getFileName().toString());
    }

    private static List<Path> enabledFiles(MusicContext c) {
        List<Path> out = new ArrayList<>();
        for (Path p : files(c)) if (isEnabled(c, p)) out.add(p);
        return out;
    }

    public static int enabledCount(MusicContext c) { return enabledFiles(c).size(); }
    public static boolean hasTracks(MusicContext c) { return enabledCount(c) > 0; }
    public static boolean isShuffle(MusicContext c) { return stateOf(c).shuffle; }

    // ---------------------------------------------------------------- editing

    public static void setEnabled(MusicContext c, Path file, boolean on) {
        State st = stateOf(c);
        String n = file.getFileName().toString();
        if (on) st.disabled.remove(n); else st.disabled.add(n);
        saveState();
    }

    public static void setShuffle(MusicContext c, boolean shuffle) {
        stateOf(c).shuffle = shuffle;
        CURSOR.remove(c);
        saveState();
    }

    /** Moves a track up (-1) or down (+1) in the playlist. */
    public static void move(MusicContext c, Path file, int delta) {
        State st = stateOf(c);
        int i = st.order.indexOf(file.getFileName().toString());
        int j = i + delta;
        if (i < 0 || j < 0 || j >= st.order.size()) return;
        Collections.swap(st.order, i, j);
        saveState();
        rescan(c, false);
    }

    // ---------------------------------------------------------------- playback choice

    /**
     * Next track to play. In-order mode: a fresh start (entering the context) begins at the top track,
     * then it walks down the list. Shuffle: random, never the same track twice in a row if there's a choice.
     */
    public static Path next(MusicContext c, boolean fresh) {
        List<Path> list = enabledFiles(c);
        if (list.isEmpty()) return null;
        if (list.size() == 1) return list.get(0);

        if (!isShuffle(c)) {
            int cursor = fresh ? 0 : CURSOR.getOrDefault(c, 0);
            Path pick = list.get(cursor % list.size());
            CURSOR.put(c, (cursor + 1) % list.size());
            return pick;
        }
        Path last = LAST.get(c), pick;
        do { pick = list.get(ThreadLocalRandom.current().nextInt(list.size())); } while (pick.equals(last));
        LAST.put(c, pick);
        return pick;
    }

    // engine -> sound thread hand-off
    static void queue(MusicContext c, Path file) {
        QUEUED.computeIfAbsent(c.id, k -> new java.util.concurrent.ConcurrentLinkedQueue<>()).add(file);
    }
    public static Path poll(String ctxId) {
        Queue<Path> q = QUEUED.get(ctxId);
        return q == null ? null : q.poll();
    }

    // ---------------------------------------------------------------- import / delete

    /** @return null on success, otherwise a human-readable error. */
    public static String importFile(MusicContext c, Path src) {
        String name = src.getFileName().toString();
        if (!name.toLowerCase().endsWith(".ogg")) return "Only .ogg (Vorbis) files work: " + name;
        if (!OggInfo.isVorbis(src)) return "Not Ogg Vorbis (Opus/other?): " + name;
        try {
            Files.createDirectories(dir(c));
            Files.copy(src, dir(c).resolve(name), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            return "Copy failed: " + e.getMessage();
        }
        rescan(c);
        return null;
    }

    public static void remove(MusicContext c, Path file) {
        try { Files.deleteIfExists(file); } catch (IOException ignored) { }
        rescan(c);
    }
}
