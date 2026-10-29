package com.pulsewave;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Never actually sent. A server that has Pulsewave registers a receiver for this channel, and a client
 * can see that with ClientPlayNetworking.canSend(...), which is how "Automatic" detection finds out.
 */
public record HelloPayload() implements CustomPayload {
    public static final Id<HelloPayload> ID = new Id<>(Identifier.of(PulsewaveMod.MOD_ID, "hello"));
    public static final PacketCodec<RegistryByteBuf, HelloPayload> CODEC = PacketCodec.unit(new HelloPayload());

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
