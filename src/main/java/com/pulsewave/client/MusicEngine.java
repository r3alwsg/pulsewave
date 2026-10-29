package com.pulsewave.client;

import com.pulsewave.CombatSignalPayload;
import com.pulsewave.HelloPayload;
import com.pulsewave.PulsewaveConfig;
import com.pulsewave.MusicContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.JukeboxBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.CreditsScreen;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.mob.ElderGuardianEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeKeys;

import com.pulsewave.PulsewaveMod;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Evaluates the game state every tick and drives two channels:
 *   base    - menu / credits / dimension / underground / weather / time of day
 *   overlay - boss > PVP > PVE combat (ducks the base channel)
 */
public final class MusicEngine {
    public static final MusicEngine INSTANCE = new MusicEngine();

    private final Map<MusicContext, Long> brokenUntil = new EnumMap<>(MusicContext.class);
    private final MusicChannel base = new MusicChannel(true, this::markBroken, (c, f) -> announce(f, false));
    private final MusicChannel overlay = new MusicChannel(false, this::markBroken, (c, f) -> announce(f, true));
    private boolean overlayActive;
    // combat detection: server signals vs. this game's own detection
    private boolean serverHasMod;
    private boolean useServer;
    private int modCheck;
    private int lastHurtTime;
    private int pveScanCooldown;
    private boolean jukeboxActive;
    private boolean jukeboxNear;
    private int jukeboxScanCooldown;
    private static final double JUKEBOX_RANGE = 48.0;

    private final java.util.Set<MusicContext> warned = EnumSet.noneOf(MusicContext.class);
    private long tick;
    private long pveUntil, pvpUntil, intenseUntil, ancientUntil, trialUntil, lowUntil;
    private final Map<MusicContext, Long> bossUntil = new EnumMap<>(MusicContext.class);
    private static final MusicContext[] BOSS_ORDER = {
            MusicContext.BOSS_WITHER, MusicContext.BOSS_ENDER_DRAGON, MusicContext.BOSS_ELDER_GUARDIAN};
    private int pveCount;
    private List<String> debug = List.of();
    private boolean lowHealthState;
    private ClientWorld lastWorld;
    private RegistryKey<World> lastDim;
    private boolean vanillaStopped;
    private MusicContext bossCtx;
    private int bossScanCooldown;
    private int bossTicks;

    /** How many ticks a situation stays active, from the player's own setting (never shorter than minTicks). */
    private int holdTicks(MusicContext c, int minTicks) {
        return Math.max(minTicks, PulsewaveConfig.INSTANCE.ticks(PulsewaveConfig.INSTANCE.holdFor(c)));
    }

    private void applyPvp() {
        pvpUntil = Math.max(pvpUntil, tick + holdTicks(MusicContext.COMBAT_PVP, 10));
    }

    private void applyPve(int count) {
        pveUntil = Math.max(pveUntil, tick + holdTicks(MusicContext.COMBAT_PVE, 20));
        pveCount = count;
        if (count >= PulsewaveConfig.INSTANCE.intenseMobCount) {
            intenseUntil = Math.max(intenseUntil, tick + holdTicks(MusicContext.COMBAT_INTENSE, 20));
        }
    }

    /** The server only says "this is happening"; how long it lasts afterwards is your own setting. */
    public void onSignal(CombatSignalPayload p) {
        byte type = p.type();
        // Combat signals only count when server detection is the active mode (one source at a time).
        if ((type == CombatSignalPayload.PVP || type == CombatSignalPayload.PVE) && !useServer) return;
        switch (type) {
            case CombatSignalPayload.PVP -> applyPvp();
            case CombatSignalPayload.ANCIENT_CITY ->
                    ancientUntil = Math.max(ancientUntil, tick + holdTicks(MusicContext.ANCIENT_CITY, 40));
            case CombatSignalPayload.TRIAL_CHAMBERS ->
                    trialUntil = Math.max(trialUntil, tick + holdTicks(MusicContext.TRIAL_CHAMBERS, 40));
            default -> applyPve(p.count());   // PVE: the server repeats this twice a second while monsters target you
        }
    }

    /** From the attack event: you hit a player (PVP) or a monster (PVE). Only used by client detection. */
    public void onLocalAttack(PlayerEntity attacker, Entity target) {
        if (useServer) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || attacker != mc.player) return;
        if (target instanceof PlayerEntity other) {
            if (other != attacker && !attacker.isTeammate(other)) applyPvp();
        } else if (target instanceof Monster) {
            applyPve(1);
        }
    }

    /** Does the server we are on have Pulsewave? Singleplayer / LAN hosts always do. */
    private boolean detectServerMod(MinecraftClient mc) {
        if (mc.world == null) return false;
        if (mc.isInSingleplayer()) return true;
        try {
            return mc.getNetworkHandler() != null && ClientPlayNetworking.canSend(HelloPayload.ID);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Client-side combat detection, for servers without Pulsewave (or when you pick Client mode):
     *  PVP - a non-teammate player next to you was swinging when you got hurt (plus the "you hit a player" event)
     *  PVE - monsters in "attacking" state near you, or a monster close by when you got hurt
     */
    private void clientDetect(MinecraftClient mc, ClientWorld w) {
        var me = mc.player;
        if (me == null || me.isDead() || me.isSpectator()) return;

        boolean justHurt = me.hurtTime > 0 && lastHurtTime == 0;
        lastHurtTime = me.hurtTime;

        if (justHurt) {
            for (PlayerEntity other : w.getPlayers()) {
                if (other == me || other.isSpectator() || me.isTeammate(other)) continue;
                if (me.squaredDistanceTo(other) <= 49.0 && other.handSwinging) {
                    applyPvp();
                    break;
                }
            }
        }

        if (--pveScanCooldown > 0 && !justHurt) return;
        pveScanCooldown = 10;
        int count = 0;
        for (Entity e : w.getEntities()) {
            if (!(e instanceof MobEntity m) || !(m instanceof Monster) || !m.isAlive()) continue;
            double d = me.squaredDistanceTo(m);
            if (d <= 24.0 * 24.0 && m.isAttacking()) count++;
            else if (justHurt && d <= 64.0) count++;
        }
        if (count > 0) applyPve(count);
    }

    private int errorCount;

    /** Wrapped so that a bug in here can never crash the game: it is logged and the tick is skipped. */
    public void tick(MinecraftClient mc) {
        try {
            tickInner(mc);
        } catch (RuntimeException e) {
            if (errorCount++ < 5) PulsewaveMod.LOGGER.error("Pulsewave: music engine error (game keeps running)", e);
        }
    }

    private void tickInner(MinecraftClient mc) {
        PreviewPlayer.tick(mc);
        PulsewaveConfig cfg = PulsewaveConfig.INSTANCE;
        if (!cfg.enabled) {
            hardReset(mc);
            return;
        }
        tick++;
        if (!vanillaStopped && cfg.suppressVanillaMusic) {
            mc.getMusicTracker().stop();
            vanillaStopped = true;
        }

        // Portals / world change: clean audio reset.
        ClientWorld w = mc.world;
        RegistryKey<World> dim = w != null ? w.getRegistryKey() : null;
        if (w != lastWorld || (dim != null && !dim.equals(lastDim))) {
            if (lastWorld != null && w != null) hardReset(mc);
            pveUntil = pvpUntil = intenseUntil = ancientUntil = trialUntil = 0;
            lowHealthState = false;
            lowUntil = 0;
            serverHasMod = false;
            modCheck = 0;
            lastHurtTime = 0;
            bossUntil.clear();
            bossCtx = null;
            bossTicks = 0;
            lastWorld = w;
            lastDim = dim;
        }
        if (mc.player != null && mc.player.isDead()) {
            pveUntil = pvpUntil = intenseUntil = 0;
            lowHealthState = false;
        }

        // Combat detection source: server signals or this game's own detection
        if (--modCheck <= 0) {
            modCheck = 20;
            serverHasMod = detectServerMod(mc);
        }
        useServer = switch (cfg.combatMode()) {
            case SERVER -> true;
            case CLIENT -> false;
            case AUTO -> serverHasMod;
        };
        if (!useServer && w != null) clientDetect(mc, w);

        List<String> skippedBase = new ArrayList<>();
        List<String> skippedOverlay = new ArrayList<>();
        MusicContext baseCtx = pick(mc, baseCandidates(mc, w), skippedBase);
        MusicContext overlayCtx = pick(mc, overlayCandidates(mc, w), skippedOverlay);

        // Boss fights: release base music once the fight drags on.
        if (overlayCtx != null && overlayCtx.isBoss()) bossTicks++; else bossTicks = 0;
        if (cfg.bossReleaseSeconds > 0 && bossTicks > cfg.bossReleaseSeconds * 20) baseCtx = null;

        overlayActive = overlayCtx != null;
        // Extra muting on top of combat ducking: a jukebox playing nearby, or a preview in the manager.
        jukeboxActive = cfg.jukeboxDucking && scanJukeboxes(mc, w);
        float external = PreviewPlayer.isActive() ? 0f : (jukeboxActive ? cfg.jukeboxDuckVolume : 1f);
        base.setDuck((overlayCtx != null ? cfg.combatDuckVolume : 1f) * external);
        overlay.setDuck(external);
        base.update(mc, cfg, baseCtx);
        overlay.update(mc, cfg, overlayCtx);

        if (cfg.debugOverlay) debug = buildDebug(mc, cfg, baseCtx, overlayCtx, skippedBase, skippedOverlay, external);
    }

    /** Is a jukebox with a disc playing within hearing distance? Checked twice a second. */
    private boolean scanJukeboxes(MinecraftClient mc, ClientWorld w) {
        if (--jukeboxScanCooldown > 0) return jukeboxNear;
        jukeboxScanCooldown = 10;
        jukeboxNear = false;
        if (w == null || mc.player == null) return false;

        BlockPos pp = mc.player.getBlockPos();
        int pcx = pp.getX() >> 4, pcz = pp.getZ() >> 4;
        double r2 = JUKEBOX_RANGE * JUKEBOX_RANGE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                WorldChunk chunk = w.getChunkManager().getWorldChunk(pcx + dx, pcz + dz);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof JukeboxBlockEntity jb && jb.getManager().isPlaying()
                            && mc.player.squaredDistanceTo(Vec3d.ofCenter(be.getPos())) <= r2) {
                        jukeboxNear = true;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- evaluation

    private MusicContext[] baseCandidates(MinecraftClient mc, ClientWorld w) {
        if (mc.currentScreen instanceof CreditsScreen) return new MusicContext[]{MusicContext.CREDITS};
        if (w == null || mc.player == null) return new MusicContext[]{MusicContext.MENU};

        RegistryKey<World> key = w.getRegistryKey();
        boolean creative = mc.player.isCreative();
        if (key == World.NETHER) return withCreative(creative, MusicContext.NETHER);
        if (key == World.END) return withCreative(creative, MusicContext.END);

        BlockPos pos = mc.player.getBlockPos();
        var biome = w.getBiome(pos);
        boolean underground = pos.getY() < w.getSeaLevel() && w.getLightLevel(LightType.SKY, pos) <= 1;
        boolean inOcean = biome.isIn(BiomeTags.IS_OCEAN);

        // Highest priority first; pick() takes the first one that actually has music.
        List<MusicContext> list = new ArrayList<>();
        if (tick < ancientUntil) list.add(MusicContext.ANCIENT_CITY);     // told to us by the server
        if (tick < trialUntil) list.add(MusicContext.TRIAL_CHAMBERS);
        // One folder for all water: in an ocean biome (surface and sea floor) or with your head under water.
        if (inOcean || mc.player.isSubmergedInWater()) list.add(MusicContext.WATER);
        if (!inOcean && underground) {
            if (biome.matchesKey(BiomeKeys.DEEP_DARK)) list.add(MusicContext.DEEP_DARK);
            list.add(MusicContext.CAVE);
        }
        if (!underground && w.getRainGradient(1f) > 0.2f) list.add(MusicContext.RAIN);
        list.add(timeOfDay(w));
        if (creative) list.add(0, MusicContext.CREATIVE);   // creative mode wins over everything else here
        return list.toArray(new MusicContext[0]);
    }

    /** In creative mode the Creative folder comes first; the normal music is the fallback if it's empty. */
    private static MusicContext[] withCreative(boolean creative, MusicContext normal) {
        return creative ? new MusicContext[]{MusicContext.CREATIVE, normal} : new MusicContext[]{normal};
    }

    private static MusicContext timeOfDay(ClientWorld w) {
        long t = Math.floorMod(w.getTimeOfDay(), 24000L);
        if (t >= 23000 || t < 1000) return MusicContext.OVERWORLD_DAWN;
        if (t < 12000) return MusicContext.OVERWORLD_DAY;
        if (t < 13500) return MusicContext.OVERWORLD_DUSK;
        return MusicContext.OVERWORLD_NIGHT;
    }

    private MusicContext[] overlayCandidates(MinecraftClient mc, ClientWorld w) {
        if (w == null || mc.player == null || mc.currentScreen instanceof CreditsScreen) return new MusicContext[0];

        // Highest priority first: boss > low health > PVP > swarm > PVE
        List<MusicContext> list = new ArrayList<>();
        MusicContext boss = scanBoss(mc, w);
        if (boss != null) list.add(boss);
        for (MusicContext b : BOSS_ORDER) {            // a boss that just went away lingers for its hold time
            if (b != boss && tick < bossUntil.getOrDefault(b, 0L)) list.add(b);
        }
        if (isLowHealth(mc)) list.add(MusicContext.LOW_HEALTH);
        if (tick < pvpUntil) list.add(MusicContext.COMBAT_PVP);
        if (tick < intenseUntil) list.add(MusicContext.COMBAT_INTENSE);
        if (tick < pveUntil) list.add(MusicContext.COMBAT_PVE);
        return list.toArray(new MusicContext[0]);
    }

    /** Low health with hysteresis: starts at the threshold, ends 2 hearts above it. */
    private boolean isLowHealth(MinecraftClient mc) {
        var p = mc.player;
        if (p == null || p.isDead() || p.isCreative() || p.isSpectator()) {
            lowHealthState = false;
            lowUntil = 0;
            return false;
        }
        float threshold = PulsewaveConfig.INSTANCE.lowHealthHearts * 2f;
        float hp = p.getHealth();
        if (lowHealthState) {
            if (hp > threshold + 4f) lowHealthState = false;
        } else if (hp <= threshold) {
            lowHealthState = true;
        }
        if (lowHealthState) lowUntil = tick + holdTicks(MusicContext.LOW_HEALTH, 0);
        return lowHealthState || tick < lowUntil;
    }

    /** Looks for the nearest living boss; scanned twice a second. */
    private MusicContext scanBoss(MinecraftClient mc, ClientWorld w) {
        if (--bossScanCooldown > 0) return bossCtx;
        bossScanCooldown = 10;
        PulsewaveConfig cfg = PulsewaveConfig.INSTANCE;
        double bestD = Double.MAX_VALUE;
        MusicContext found = null;
        for (Entity e : w.getEntities()) {
            MusicContext c = e instanceof WitherEntity ? MusicContext.BOSS_WITHER
                    : e instanceof EnderDragonEntity ? MusicContext.BOSS_ENDER_DRAGON
                    : e instanceof ElderGuardianEntity ? MusicContext.BOSS_ELDER_GUARDIAN : null;
            if (c == null || !e.isAlive()) continue;
            double d = mc.player.squaredDistanceTo(e);
            double range = cfg.bossRangeFor(c);
            if (d > range * range) continue;                       // each boss has its own range
            bossUntil.put(c, tick + holdTicks(c, 20));             // ...and its own hold time
            if (d < bestD) { bestD = d; found = c; }
        }
        return bossCtx = found;
    }

    /** First candidate that has a sound and hasn't recently failed to play. */
    private MusicContext pick(MinecraftClient mc, MusicContext[] candidates, List<String> skipped) {
        for (MusicContext c : candidates) {
            if (c == null || c.event() == null) continue;
            if (brokenUntil.getOrDefault(c, 0L) > tick) { skipped.add(c.id + " (failed to play)"); continue; }
            if (!UserMusic.hasTracks(c)) {
                skipped.add(c.id + (UserMusic.files(c).isEmpty() ? " (no music)" : " (all tracks off)"));
                continue;
            }
            if (mc.getSoundManager().get(c.event().id()) == null) {
                if (warned.add(c)) PulsewaveMod.LOGGER.warn("Pulsewave: sound event for {} is not registered with the sound manager", c.id);
                skipped.add(c.id + " (not registered)");
                continue;
            }
            return c;
        }
        return null;
    }

    public List<String> debugLines() { return debug; }

    private List<String> buildDebug(MinecraftClient mc, PulsewaveConfig cfg, MusicContext baseCtx, MusicContext overlayCtx,
                                    List<String> skippedBase, List<String> skippedOverlay, float external) {
        List<String> out = new ArrayList<>();
        out.add("Pulsewave debug  (F6 hides)");
        out.add("Base: " + base.describe());
        out.add("  wants " + (baseCtx == null ? "nothing" : baseCtx.id)
                + (skippedBase.isEmpty() ? "" : "   skipped: " + String.join(", ", skippedBase)));
        out.add("Overlay: " + overlay.describe());
        out.add("  wants " + (overlayCtx == null ? "nothing" : overlayCtx.id)
                + (skippedOverlay.isEmpty() ? "" : "   skipped: " + String.join(", ", skippedOverlay)));
        float hp = mc.player != null ? mc.player.getHealth() : 0f;
        out.add("PVE " + (tick < pveUntil ? "on (" + pveCount + " mobs)" : "off")
                + "  swarm " + (tick < intenseUntil ? "on" : "off")
                + "  PVP " + (tick < pvpUntil ? "on" : "off")
                + "  low hp " + (lowHealthState ? "on" : "off") + " (" + Math.round(hp) + ")");
        out.add("Combat detection: " + (useServer ? "server" : "client")
                + " (" + cfg.combatMode().name().toLowerCase()
                + (cfg.combatMode() == PulsewaveConfig.CombatDetection.AUTO ? (serverHasMod ? ", server has Pulsewave" : ", no Pulsewave on server") : "") + ")");
        out.add("Structure " + (tick < ancientUntil ? "ancient city" : tick < trialUntil ? "trial chambers" : "none"));
        out.add("Jukebox " + (jukeboxActive ? "near" : "no")
                + "  preview " + (PreviewPlayer.isActive() ? "yes" : "no")
                + "  boss " + (bossTicks / 20) + "s"
                + "  extra duck " + Math.round(external * 100) + "%");
        return out;
    }

    /** "Now playing" popup, unless switched off or we're in combat. */
    private void announce(Path file, boolean fromOverlay) {
        PulsewaveConfig cfg = PulsewaveConfig.INSTANCE;
        if (!cfg.showNowPlaying || jukeboxActive) return;
        if (cfg.hideNowPlayingInCombat && (fromOverlay || overlayActive)) return;
        showToast(prettyName(file));
    }

    private static String prettyName(Path file) {
        return file.getFileName().toString().replaceFirst("(?i)\\.ogg$", "").replace('_', ' ');
    }

    /** Also used by the Test popup button in the Options tab. */
    public static void showToast(String trackName) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        SystemToast.add(mc.getToastManager(), SystemToast.Type.PERIODIC_NOTIFICATION,
                Text.literal("Now playing"), Text.literal(trackName));
    }

    private void markBroken(MusicContext ctx) {
        brokenUntil.put(ctx, tick + 20L * 60); // retry in a minute (e.g. after a resource reload)
    }

    private void hardReset(MinecraftClient mc) {
        base.reset(mc);
        overlay.reset(mc);
    }
}
