package com.pulsewave.client;

import com.pulsewave.MusicContext;
import com.pulsewave.PulsewaveConfig;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;

/**
 * Three tabs:
 *   Music   - pick a folder (context) on the left, add / remove .ogg files for it on the right.
 *   Timing  - fade in / fade out / next-music delay / end-of-track fade, transition mode, units.
 *   Options - "Now playing" popup switches.
 */
public class PulsewaveMusicScreen extends Screen {
    static final Identifier LOGO = Identifier.of("pulsewave", "textures/gui/logo.png");
    private static final int TOP = 46;        // top of the panels
    private static final int PANEL_H = 190;
    private static final int MAX_ROWS = 4;
    private static final int TAB_MUSIC = 0, TAB_TIMING = 1, TAB_OPTIONS = 2, TAB_HOLD = 3;

    private static MusicContext remembered = MusicContext.values()[0];
    private static int rememberedTab = TAB_MUSIC;

    private final Screen parent;
    private MusicContext selected = remembered;
    private int tab = rememberedTab;
    private Text status = Text.empty();
    private int page;                       // track-list page on the Music tab
    private Path lastPreview;               // to notice when a preview starts / ends

    public PulsewaveMusicScreen(Screen parent) {
        super(Text.translatable("screen.pulsewave.title"));
        this.parent = parent;
    }

    private int leftX() { return width / 2 - 205; }
    private int rightX() { return width / 2 + 5; }

    // ================================================================ layout

    @Override
    protected void init() {
        UserMusic.rescanAll();

        addDrawableChild(tabButton("Music", TAB_MUSIC, width / 2 - 3));
        addDrawableChild(tabButton("Timing", TAB_TIMING, width / 2 + 49));
        addDrawableChild(tabButton("Hold", TAB_HOLD, width / 2 + 101));
        addDrawableChild(tabButton("Options", TAB_OPTIONS, width / 2 + 153));

        if (tab == TAB_MUSIC) initMusicTab();
        else if (tab == TAB_TIMING) initTimingTab();
        else if (tab == TAB_HOLD) initHoldTab();
        else initOptionsTab();

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(width / 2 - 50, height - 26, 100, 20).build());
    }

    private ButtonWidget tabButton(String name, int index, int x) {
        ButtonWidget b = ButtonWidget.builder(Text.literal(name), btn -> {
            tab = rememberedTab = index;
            status = Text.empty();
            refresh();
        }).dimensions(x, 9, 50, 20).build();
        b.active = tab != index;
        return b;
    }

    private void refresh() {
        clearChildren();
        init();
    }

    // ---------------------------------------------------------------- music tab

    private void initMusicTab() {
        MusicContext[] all = MusicContext.values();
        for (int i = 0; i < all.length; i++) {
            MusicContext c = all[i];
            ButtonWidget b = ButtonWidget.builder(label(c), btn -> select(c))
                    .dimensions(leftX() + (i / 11) * 100, TOP + 4 + (i % 11) * 16, 98, 14).build();
            b.active = c != selected;
            addDrawableChild(b);
        }

        int px = rightX();
        addDrawableChild(ButtonWidget.builder(Text.literal("Add .ogg"), b -> pickFiles())
                .dimensions(px, TOP + 16, 63, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Folder"), b -> openFolder())
                .dimensions(px + 66, TOP + 16, 63, 20).build());

        boolean shuffle = UserMusic.isShuffle(selected);
        ButtonWidget order = ButtonWidget.builder(Text.literal(shuffle ? "Shuffle" : "In order"), b -> {
            UserMusic.setShuffle(selected, !shuffle);
            status = Text.literal(shuffle ? "In order: top track plays first." : "Shuffle: random track each time.");
            refresh();
        }).dimensions(px + 132, TOP + 16, 63, 20).build();
        order.setTooltip(Tooltip.of(Text.literal(shuffle
                ? "Shuffle: a random track each time. Click for in-order."
                : "In order: the top track plays first, then down the list. Click for shuffle.")));
        addDrawableChild(order);

        List<Path> files = UserMusic.files(selected);
        int pages = Math.max(1, (files.size() + MAX_ROWS - 1) / MAX_ROWS);
        page = Math.max(0, Math.min(page, pages - 1));
        lastPreview = PreviewPlayer.currentFile();

        for (int i = 0; i < MAX_ROWS; i++) {
            final int idx = page * MAX_ROWS + i;
            if (idx >= files.size()) break;
            final Path f = files.get(idx);
            int y = TOP + 54 + i * 22;
            boolean on = UserMusic.isEnabled(selected, f);
            boolean previewing = f.equals(PreviewPlayer.currentFile());

            ButtonWidget toggle = ButtonWidget.builder(Text.literal(on ? "On" : "Off"), b -> {
                UserMusic.setEnabled(selected, f, !on);
                refresh();
            }).dimensions(px, y, 24, 20).build();
            toggle.setTooltip(Tooltip.of(Text.literal(on ? "On: click to switch this track off." : "Off: click to switch this track on.")));
            addDrawableChild(toggle);

            ButtonWidget preview = ButtonWidget.builder(Text.literal(previewing ? "\u25A0" : "\u25B6"), b -> {
                if (previewing) PreviewPlayer.stop(client);
                else PreviewPlayer.play(client, selected, f);
                refresh();
            }).dimensions(px + 26, y, 18, 20).build();
            preview.setTooltip(Tooltip.of(Text.literal(previewing ? "Stop preview" : "Preview this track (other music is muted)")));
            addDrawableChild(preview);

            ButtonWidget up = ButtonWidget.builder(Text.literal("\u25B2"), b -> {
                UserMusic.move(selected, f, -1);
                refresh();
            }).dimensions(px + 139, y, 16, 20).build();
            up.active = idx > 0;
            up.setTooltip(Tooltip.of(Text.literal("Move up (plays earlier)")));
            addDrawableChild(up);

            ButtonWidget down = ButtonWidget.builder(Text.literal("\u25BC"), b -> {
                UserMusic.move(selected, f, 1);
                refresh();
            }).dimensions(px + 157, y, 16, 20).build();
            down.active = idx < files.size() - 1;
            down.setTooltip(Tooltip.of(Text.literal("Move down (plays later)")));
            addDrawableChild(down);

            addDrawableChild(ButtonWidget.builder(Text.literal("X"), b -> {
                if (previewing) PreviewPlayer.stop(client);
                UserMusic.remove(selected, f);
                status = Text.literal("Removed " + f.getFileName());
                refresh();
            }).dimensions(px + 175, y, 20, 20).build());
        }

        // per-folder volume
        PulsewaveConfig cfg = PulsewaveConfig.INSTANCE;
        IntSlider volume = new IntSlider(px, TOP + 146, 96, "Volume", "%", 0, 100,
                Math.round(cfg.folderVolume(selected) * 100), v -> cfg.folderVolume.put(selected.id, v / 100f));
        volume.setTooltip(Tooltip.of(Text.literal("Volume of this folder (on top of the Music slider).")));
        addDrawableChild(volume);
        FolderFadeSlider fade = new FolderFadeSlider(px + 99, TOP + 146, 96, selected);
        fade.setTooltip(Tooltip.of(Text.literal("Fade in/out time for this folder. Auto = use the Timing tab values.")));
        addDrawableChild(fade);

        if (pages > 1) {
            ButtonWidget prev = ButtonWidget.builder(Text.literal("<"), b -> { page--; refresh(); })
                    .dimensions(px + 55, TOP + 170, 20, 16).build();
            prev.active = page > 0;
            addDrawableChild(prev);
            ButtonWidget next = ButtonWidget.builder(Text.literal(">"), b -> { page++; refresh(); })
                    .dimensions(px + 120, TOP + 170, 20, 16).build();
            next.active = page < pages - 1;
            addDrawableChild(next);
        }
    }

    private void select(MusicContext c) {
        selected = remembered = c;
        page = 0;
        status = Text.empty();
        refresh();
    }

    private Text label(MusicContext c) {
        return Text.literal(pretty(c) + " (" + UserMusic.files(c).size() + ")");
    }

    private static String pretty(MusicContext c) {
        return switch (c) {
            case MENU -> "Menu";
            case CREDITS -> "Credits";
            case OVERWORLD_DAWN -> "Dawn";
            case OVERWORLD_DAY -> "Day";
            case OVERWORLD_DUSK -> "Dusk";
            case OVERWORLD_NIGHT -> "Night";
            case RAIN -> "Rain";
            case WATER -> "Ocean & Water";
            case CAVE -> "Cave";
            case DEEP_DARK -> "Deep Dark";
            case ANCIENT_CITY -> "Ancient City";
            case TRIAL_CHAMBERS -> "Trial Chambers";
            case NETHER -> "Nether";
            case END -> "The End";
            case CREATIVE -> "Creative Mode";
            case COMBAT_PVE -> "Combat PVE";
            case COMBAT_INTENSE -> "Swarm Combat";
            case COMBAT_PVP -> "Combat PVP";
            case LOW_HEALTH -> "Low Health";
            case BOSS_WITHER -> "Boss: Wither";
            case BOSS_ENDER_DRAGON -> "Boss: Dragon";
            case BOSS_ELDER_GUARDIAN -> "Boss: Guardian";
        };
    }

    // ---------------------------------------------------------------- timing tab

    private void initTimingTab() {
        PulsewaveConfig c = PulsewaveConfig.INSTANCE;
        int x = width / 2 - 150, w = 300;

        addDrawableChild(new TimeSlider(x, TOP + 6, w, "Fade in", 10, c.fadeInSeconds,
                v -> c.fadeInSeconds = (float) v));
        addDrawableChild(new TimeSlider(x, TOP + 30, w, "Fade out", 10, c.fadeOutSeconds,
                v -> c.fadeOutSeconds = (float) v));
        addDrawableChild(new TimeSlider(x, TOP + 54, w, "Next music starts after", 20, c.gapSeconds,
                v -> c.gapSeconds = (float) v));
        addDrawableChild(new TimeSlider(x, TOP + 78, w, "End-of-track fade-out", 10, c.endOfTrackFadeSeconds,
                v -> c.endOfTrackFadeSeconds = (float) v));

        ButtonWidget cross = ButtonWidget.builder(
                        Text.literal("End crossfade: " + onOff(c.crossfadeAtEnd)), b -> {
                            c.crossfadeAtEnd = !c.crossfadeAtEnd;
                            refresh();
                        })
                .dimensions(x, TOP + 102, 148, 20).build();
        cross.setTooltip(Tooltip.of(Text.literal("The next song fades in before the current one ends, so there is no silence between them.")));
        addDrawableChild(cross);
        addDrawableChild(new TimeSlider(x + 152, TOP + 102, 148, "Overlap", 10, c.crossfadeSeconds,
                v -> c.crossfadeSeconds = (float) v));

        addDrawableChild(ButtonWidget.builder(
                        Text.literal("Transition: " + pretty(c.mode().name())), b -> {
                            PulsewaveConfig.Mode[] m = PulsewaveConfig.Mode.values();
                            c.transitionMode = m[(c.mode().ordinal() + 1) % m.length].name();
                            refresh();
                        })
                .dimensions(x, TOP + 126, 148, 20).build());
        addDrawableChild(ButtonWidget.builder(
                        Text.literal("Units: " + (c.timingInMilliseconds ? "Milliseconds" : "Seconds")), b -> {
                            c.timingInMilliseconds = !c.timingInMilliseconds;
                            refresh();
                        })
                .dimensions(x + 152, TOP + 126, 148, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Reset timing"), b -> {
                    c.fadeInSeconds = 3f;
                    c.fadeOutSeconds = 3f;
                    c.gapSeconds = 2f;
                    c.endOfTrackFadeSeconds = 4f;
                    c.crossfadeAtEnd = true;
                    c.crossfadeSeconds = 4f;
                    c.transitionMode = "HYBRID";
                    refresh();
                })
                .dimensions(x, TOP + 150, 148, 20).build());
    }

    // ---------------------------------------------------------------- hold tab

    private static final MusicContext[] HOLD_LEFT = {
            MusicContext.COMBAT_PVE, MusicContext.COMBAT_INTENSE, MusicContext.COMBAT_PVP,
            MusicContext.LOW_HEALTH, MusicContext.ANCIENT_CITY, MusicContext.TRIAL_CHAMBERS};
    private static final String[] HOLD_LEFT_NAMES = {
            "PVE hold", "Swarm hold", "PVP hold", "Low health hold", "City hold", "Trial hold"};
    private static final MusicContext[] BOSSES = {
            MusicContext.BOSS_WITHER, MusicContext.BOSS_ENDER_DRAGON, MusicContext.BOSS_ELDER_GUARDIAN};
    private static final String[] BOSS_NAMES = {"Wither", "Dragon", "Guardian"};

    /** "Hold" = how long a situation stays active after it stops. Works in multiplayer: it is applied on your side. */
    private void initHoldTab() {
        PulsewaveConfig c = PulsewaveConfig.INSTANCE;
        int xl = width / 2 - 150, xr = width / 2 + 2, w = 148;

        for (int i = 0; i < HOLD_LEFT.length; i++) {
            MusicContext m = HOLD_LEFT[i];
            addDrawableChild(new TimeSlider(xl, TOP + 6 + i * 24, w, HOLD_LEFT_NAMES[i], 20, c.holdFor(m),
                    v -> c.holdSeconds.put(m.id, (float) v)));
        }
        for (int i = 0; i < BOSSES.length; i++) {
            MusicContext m = BOSSES[i];
            addDrawableChild(new TimeSlider(xr, TOP + 6 + i * 24, w, BOSS_NAMES[i] + " hold", 20, c.holdFor(m),
                    v -> c.holdSeconds.put(m.id, (float) v)));
            addDrawableChild(new IntSlider(xr, TOP + 78 + i * 24, w, BOSS_NAMES[i] + " range", " m", 16, 256,
                    c.bossRangeFor(m), v -> c.bossRangeByBoss.put(m.id, v)));
        }
        ButtonWidget detect = ButtonWidget.builder(
                Text.literal("Detection: " + pretty(c.combatMode().name())), b -> {
                    PulsewaveConfig.CombatDetection[] m = PulsewaveConfig.CombatDetection.values();
                    c.combatDetection = m[(c.combatMode().ordinal() + 1) % m.length].name();
                    refresh();
                }).dimensions(xr, TOP + 150, w, 20).build();
        detect.setTooltip(Tooltip.of(Text.literal(
                "How PVP / PVE combat is detected. Auto: the server if it has Pulsewave, otherwise your own game. "
                        + "Client: always your own game (works on any server, PVE is less exact). "
                        + "Server: only the server's signals (most exact, needs the mod there).")));
        addDrawableChild(detect);
        addDrawableChild(ButtonWidget.builder(Text.literal("Reset holds"), b -> {
                    c.holdSeconds.clear();
                    c.bossRangeByBoss.clear();
                    refresh();
                })
                .dimensions(xl, TOP + 150, w, 20).build());
    }

    private void renderHoldTab(DrawContext ctx) {
        int cx = width / 2;
        String l1 = "Hold = how long a situation stays on after it stops.";
        String l2 = "Range = how close a boss must be. Applies on your side.";
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(l1, 310)), cx, TOP + 173, 0xFF999999);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(l2, 310)), cx, TOP + 181, 0xFF999999);
    }

    // ---------------------------------------------------------------- options tab

    private void initOptionsTab() {
        PulsewaveConfig c = PulsewaveConfig.INSTANCE;
        int x = width / 2 - 150, w = 300;

        addDrawableChild(ButtonWidget.builder(
                        Text.literal("Now playing popup: " + onOff(c.showNowPlaying)), b -> {
                            c.showNowPlaying = !c.showNowPlaying;
                            refresh();
                        })
                .dimensions(x, TOP + 6, w, 20).build());
        addDrawableChild(ButtonWidget.builder(
                        Text.literal("Hide in combat: " + onOff(c.hideNowPlayingInCombat)), b -> {
                            c.hideNowPlayingInCombat = !c.hideNowPlayingInCombat;
                            refresh();
                        })
                .dimensions(x, TOP + 30, 148, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Test popup"),
                        b -> MusicEngine.showToast("Example track"))
                .dimensions(x + 152, TOP + 30, 148, 20).build());

        addDrawableChild(ButtonWidget.builder(
                        Text.literal("Jukebox ducking: " + onOff(c.jukeboxDucking)), b -> {
                            c.jukeboxDucking = !c.jukeboxDucking;
                            refresh();
                        })
                .dimensions(x, TOP + 58, w, 20).build());
        addDrawableChild(new IntSlider(x, TOP + 82, w, "Volume near a jukebox", "%", 0, 100,
                Math.round(c.jukeboxDuckVolume * 100), v -> c.jukeboxDuckVolume = v / 100f));

        addDrawableChild(new IntSlider(x, TOP + 102, w, "Low Health music below", " hearts", 1, 9,
                c.lowHealthHearts, v -> c.lowHealthHearts = v));
        addDrawableChild(new IntSlider(x, TOP + 126, w, "Swarm music at", "+ mobs", 2, 15,
                c.intenseMobCount, v -> c.intenseMobCount = v));

        addDrawableChild(ButtonWidget.builder(
                        Text.literal("Debug overlay: " + onOff(c.debugOverlay)), b -> {
                            c.debugOverlay = !c.debugOverlay;
                            refresh();
                        })
                .dimensions(x, TOP + 150, w, 20).build());
    }

    /** Per-folder fade time: 0 = Auto (global values), then 0.1 s .. 10.0 s. */
    private static final class FolderFadeSlider extends SliderWidget {
        private final MusicContext folder;

        FolderFadeSlider(int x, int y, int width, MusicContext folder) {
            super(x, y, width, 20, Text.literal(""), stepOf(folder) / 100.0);
            this.folder = folder;
            updateMessage();
        }

        private static int stepOf(MusicContext c) {
            Float v = PulsewaveConfig.INSTANCE.folderFadeSeconds.get(c.id);
            return v == null ? 0 : Math.max(1, Math.min(100, Math.round(v * 10)));
        }

        private int step() { return (int) Math.round(value * 100); }

        @Override
        protected void updateMessage() {
            if (folder == null) return;
            int st = step();
            setMessage(Text.literal(st == 0 ? "Fade: Auto" : String.format(Locale.ROOT, "Fade: %.1fs", st / 10.0)));
        }

        @Override
        protected void applyValue() {
            int st = step();
            if (st == 0) PulsewaveConfig.INSTANCE.folderFadeSeconds.remove(folder.id);
            else PulsewaveConfig.INSTANCE.folderFadeSeconds.put(folder.id, st / 10f);
        }
    }

    /** Whole-number slider (hearts, mob counts). */
    private static final class IntSlider extends SliderWidget {
        private final String label, suffix;
        private final int min, max;
        private final java.util.function.IntConsumer setter;

        IntSlider(int x, int y, int width, String label, String suffix, int min, int max, int current,
                  java.util.function.IntConsumer setter) {
            super(x, y, width, 20, Text.literal(""),
                    (Math.max(min, Math.min(max, current)) - min) / (double) (max - min));
            this.label = label;
            this.suffix = suffix;
            this.min = min;
            this.max = max;
            this.setter = setter;
            updateMessage();
        }

        private int current() { return min + (int) Math.round(value * (max - min)); }

        @Override
        protected void updateMessage() {
            if (label == null) return;
            setMessage(Text.literal(label + ": " + current() + suffix));
        }

        @Override
        protected void applyValue() {
            setter.accept(current());
        }
    }

    private static String onOff(boolean on) { return on ? "On" : "Off"; }

    private static String pretty(String enumName) {
        return enumName.substring(0, 1) + enumName.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String formatTime(double seconds) {
        return PulsewaveConfig.INSTANCE.timingInMilliseconds
                ? Math.round(seconds * 1000) + " ms"
                : String.format(Locale.ROOT, "%.1f s", seconds);
    }

    /** Game ticks are 50 ms, so that's the finest step that has any effect. */
    private static double snap(double seconds) {
        double step = PulsewaveConfig.INSTANCE.timingInMilliseconds ? 0.05 : 0.1;
        return Math.round(seconds / step) * step;
    }

    private static final class TimeSlider extends SliderWidget {
        private final String label;
        private final double max;
        private final DoubleConsumer setter;

        TimeSlider(int x, int y, int width, String label, double max, double current, DoubleConsumer setter) {
            super(x, y, width, 20, Text.empty(), Math.max(0, Math.min(1, current / max)));
            this.label = label;
            this.max = max;
            this.setter = setter;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            if (label == null) return;
            setMessage(Text.literal(label + ": " + formatTime(snap(value * max))));
        }

        @Override
        protected void applyValue() {
            setter.accept(snap(value * max));
        }
    }

    // ================================================================ importing

    private void pickFiles() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(1);
            filters.put(stack.UTF8("*.ogg")).flip();
            String res = TinyFileDialogs.tinyfd_openFileDialog(
                    "Select Ogg Vorbis music for " + pretty(selected), "", filters, "Ogg Vorbis audio (*.ogg)", true);
            if (res == null) return;
            List<Path> paths = new ArrayList<>();
            for (String s : res.split("\\|")) paths.add(Path.of(s));
            importAll(paths);
        } catch (Throwable t) {
            status = Text.literal("File dialog unavailable - drag files onto this window or use Open folder.");
        }
    }

    @Override
    public void onFilesDropped(List<Path> paths) {
        if (tab != TAB_MUSIC) {
            status = Text.literal("Switch to the Music tab to add files.");
            return;
        }
        importAll(paths);
    }

    private void importAll(List<Path> paths) {
        int ok = 0;
        String error = null;
        for (Path p : paths) {
            String err = UserMusic.importFile(selected, p);
            if (err == null) ok++; else error = err;
        }
        status = error != null && ok == 0 ? Text.literal(error)
                : Text.literal("Added " + ok + " file(s) to " + pretty(selected) + (error != null ? " (some skipped)" : ""));
        refresh();
    }

    private void openFolder() {
        try {
            java.nio.file.Files.createDirectories(UserMusic.dir(selected));
            Util.getOperatingSystem().open(UserMusic.dir(selected).toUri());
        } catch (Exception e) {
            status = Text.literal("Folder: " + UserMusic.dir(selected));
        }
    }

    // ================================================================ drawing

    /** Panels and the header divider sit on top of the dimmed world, underneath the buttons. */
    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);
        int bottom = TOP + PANEL_H;
        ctx.fill(width / 2 - 205, 41, width / 2 + 205, 42, 0x55FFFFFF);
        if (tab == TAB_MUSIC) {
            ctx.fill(leftX() - 4, TOP, leftX() + 200, bottom, 0x99101018);
            ctx.fill(rightX() - 3, TOP, rightX() + 198, bottom, 0x99101018);
            ctx.fill(leftX() - 4, TOP, leftX() + 200, TOP + 1, 0xFF3FE0C5);
            ctx.fill(rightX() - 3, TOP, rightX() + 198, TOP + 1, 0xFFFF9F1C);
        } else {
            ctx.fill(width / 2 - 160, TOP, width / 2 + 160, bottom, 0x99101018);
            ctx.fill(width / 2 - 160, TOP, width / 2, TOP + 1, 0xFF3FE0C5);
            ctx.fill(width / 2, TOP, width / 2 + 160, TOP + 1, 0xFFFF9F1C);
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);

        // header: logo + title + hint on the left, tabs on the right
        int x0 = leftX();
        Text heading = title.copy().formatted(Formatting.BOLD);
        Text hint = Text.literal(tab == TAB_MUSIC ? "Add .ogg files to a folder"
                : tab == TAB_TIMING ? "Fades and gaps between tracks"
                : tab == TAB_HOLD ? "How long each situation lasts" : "Popups and extras");
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, LOGO, x0, 5, 0f, 0f, 32, 32, 256, 256, 256, 256);
        ctx.drawTextWithShadow(textRenderer, heading, x0 + 40, 10, 0xFFFFFFFF);
        ctx.drawTextWithShadow(textRenderer, hint, x0 + 40, 23, 0xFFAAAAAA);

        if (tab == TAB_MUSIC) renderMusicTab(ctx);
        else if (tab == TAB_TIMING) renderTimingTab(ctx);
        else if (tab == TAB_HOLD) renderHoldTab(ctx);
        else renderOptionsTab(ctx);

        String msg = textRenderer.trimToWidth(status.getString(), tab == TAB_MUSIC ? 192 : 300);
        if (tab == TAB_MUSIC) {
            ctx.drawTextWithShadow(textRenderer, Text.literal(msg), rightX(), TOP + 42, 0xFF55FF55);
        } else {
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(msg), width / 2, TOP + PANEL_H - 12, 0xFF55FF55);
        }
    }

    private void renderMusicTab(DrawContext ctx) {
        int px = rightX();
        List<Path> files = UserMusic.files(selected);
        int on = UserMusic.enabledCount(selected);
        ctx.drawTextWithShadow(textRenderer,
                Text.literal(pretty(selected) + "  -  " + files.size() + " track(s), " + on + " on"),
                px, TOP + 6, 0xFFFFFF55);

        boolean inOrder = !UserMusic.isShuffle(selected);
        for (int i = 0; i < MAX_ROWS; i++) {
            int idx = page * MAX_ROWS + i;
            if (idx >= files.size()) break;
            Path f = files.get(idx);
            String name = f.getFileName().toString().replaceFirst("(?i)\\.ogg$", "");
            if (inOrder) name = (idx + 1) + ". " + name;
            name = textRenderer.trimToWidth(name, 88);
            int color = UserMusic.isEnabled(selected, f) ? 0xFFFFFFFF : 0xFF777777;
            ctx.drawTextWithShadow(textRenderer, Text.literal(name), px + 48, TOP + 54 + i * 22 + 6, color);
        }

        int pages = Math.max(1, (files.size() + MAX_ROWS - 1) / MAX_ROWS);
        if (pages > 1) {
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal((page + 1) + " / " + pages),
                    px + 97, TOP + 174, 0xFF999999);
        }
        if (files.isEmpty()) {
            ctx.drawTextWithShadow(textRenderer, Text.literal("No music yet - this context is silent."),
                    px, TOP + 62, 0xFF999999);
        }
    }

    private void renderOptionsTab(DrawContext ctx) {
        int cx = width / 2;
        String[] lines = {
                "Debug overlay key: F6 (change it in Controls).",
                "Low Health / Swarm folders need music to play."
        };
        for (int i = 0; i < lines.length; i++) {
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(lines[i], 310)),
                    cx, TOP + 173 + i * 9, 0xFF999999);
        }
    }

    private void renderTimingTab(DrawContext ctx) {
        int cx = width / 2;
        String l1 = "Silence between tracks = fade-out + next delay.";
        String l2 = "End crossfade replaces the end fade-out. Folders can override fades.";
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(l1, 310)), cx, TOP + 173, 0xFF999999);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(l2, 310)), cx, TOP + 181, 0xFF999999);
    }

    @Override
    public void tick() {
        // a preview that finished on its own: update the ▶ / ■ buttons
        if (tab == TAB_MUSIC && !java.util.Objects.equals(lastPreview, PreviewPlayer.currentFile())) refresh();
    }

    @Override
    public void removed() {
        PreviewPlayer.stop(client);
        PulsewaveConfig.save();   // keep whatever was changed on the Timing tab
    }

    @Override
    public void close() {
        UserMusic.rescanAll();
        client.setScreen(parent);
    }
}
