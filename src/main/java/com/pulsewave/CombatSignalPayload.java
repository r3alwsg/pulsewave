package com.pulsewave;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> client: "you are in combat of this type for the next N ticks". */
public record CombatSignalPayload(byte type, int ticks, int count) implements CustomPayload {
    public static final byte PVE = 0;
    public static final byte PVP = 1;
    public static final byte ANCIENT_CITY = 2;
    public static final byte TRIAL_CHAMBERS = 3;

    public static final Id<CombatSignalPayload> ID = new Id<>(Identifier.of(PulsewaveMod.MOD_ID, "combat_signal"));
    public static final PacketCodec<RegistryByteBuf, CombatSignalPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.BYTE, CombatSignalPayload::type,
            PacketCodecs.VAR_INT, CombatSignalPayload::ticks,
            PacketCodecs.VAR_INT, CombatSignalPayload::count,
            CombatSignalPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
