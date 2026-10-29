package com.pulsewave;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PulsewaveMod implements ModInitializer {
    public static final String MOD_ID = "pulsewave";
    public static final Logger LOGGER = LoggerFactory.getLogger("Pulsewave");

    @Override
    public void onInitialize() {
        PulsewaveConfig.load();
        ModSounds.register();
        PayloadTypeRegistry.playS2C().register(CombatSignalPayload.ID, CombatSignalPayload.CODEC);
        // lets clients detect that this server has Pulsewave (used by Combat detection: Automatic)
        PayloadTypeRegistry.playC2S().register(HelloPayload.ID, HelloPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(HelloPayload.ID, (payload, context) -> { });
        ServerCombatTracker.init();
    }
}
