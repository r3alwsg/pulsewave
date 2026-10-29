package com.pulsewave;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.structure.StructureKeys;
import net.minecraft.util.math.Box;

/**
 * Server-side combat detection. The client can't see mob targets or who hit whom, so the
 * server tells each client "you are in PVE/PVP combat for N more ticks".
 */
public final class ServerCombatTracker {
    private ServerCombatTracker() {}

    public static void init() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register(ServerCombatTracker::onDamage);
        ServerTickEvents.END_SERVER_TICK.register(ServerCombatTracker::scanPve);
        ServerTickEvents.END_SERVER_TICK.register(ServerCombatTracker::scanStructures);
    }

    /** PVP: bidirectional, skipped for same-scoreboard-team players. */
    private static void onDamage(LivingEntity entity, DamageSource source, float baseDamage, float damage, boolean blocked) {
        if (!(entity instanceof ServerPlayerEntity victim)) return;
        if (!(source.getAttacker() instanceof ServerPlayerEntity attacker) || attacker == victim) return;

        PulsewaveConfig cfg = PulsewaveConfig.INSTANCE;
        if (cfg.pvpTeamProtection && attacker.isTeammate(victim)) return;

        int ticks = Math.max(1, cfg.ticks(cfg.pvpTimeoutSeconds));
        send(attacker, CombatSignalPayload.PVP, ticks, 0);
        send(victim, CombatSignalPayload.PVP, ticks, 0);
    }

    /** PVE: any hostile mob (Monster) currently targeting the player. Checked twice a second. */
    private static void scanPve(MinecraftServer server) {
        if (server.getTicks() % 10 != 0) return;
        PulsewaveConfig cfg = PulsewaveConfig.INSTANCE;
        int hold = Math.max(10, cfg.ticks(cfg.pveHoldSeconds));

        for (ServerWorld world : server.getWorlds()) {
            for (ServerPlayerEntity p : world.getPlayers()) {
                if (p.isSpectator() || !p.isAlive()) continue;
                if (!ServerPlayNetworking.canSend(p, CombatSignalPayload.ID)) continue;
                Box box = p.getBoundingBox().expand(cfg.pveRange);
                int targeting = world
                        .getEntitiesByClass(MobEntity.class, box,
                                m -> m instanceof Monster && m.isAlive() && m.getTarget() == p)
                        .size();
                if (targeting > 0) send(p, CombatSignalPayload.PVE, hold, targeting);
            }
        }
    }

    /** Ancient City / Trial Chambers: is the player inside the structure's actual pieces? Once a second. */
    private static void scanStructures(MinecraftServer server) {
        if (server.getTicks() % 20 != 5) return;
        for (ServerWorld world : server.getWorlds()) {
            for (ServerPlayerEntity p : world.getPlayers()) {
                if (!ServerPlayNetworking.canSend(p, CombatSignalPayload.ID)) continue;
                BlockPos pos = p.getBlockPos();
                var access = world.getStructureAccessor();
                // refreshed every second, held for 3 s, so leaving the structure ends the music soon after
                if (access.getStructureContaining(pos, entry -> entry.matchesKey(StructureKeys.ANCIENT_CITY)).hasChildren()) {
                    send(p, CombatSignalPayload.ANCIENT_CITY, 60, 0);
                }
                if (access.getStructureContaining(pos, entry -> entry.matchesKey(StructureKeys.TRIAL_CHAMBERS)).hasChildren()) {
                    send(p, CombatSignalPayload.TRIAL_CHAMBERS, 60, 0);
                }
            }
        }
    }

    private static void send(ServerPlayerEntity player, byte type, int ticks, int count) {
        if (ServerPlayNetworking.canSend(player, CombatSignalPayload.ID)) {
            ServerPlayNetworking.send(player, new CombatSignalPayload(type, ticks, count));
        }
    }
}
