package com.pulsewave;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

public final class ModSounds {
    private ModSounds() {}

    public static void register() {
        for (MusicContext ctx : MusicContext.values()) {
            Identifier id = Identifier.of(PulsewaveMod.MOD_ID, "music." + ctx.id);
            ctx.bind(Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id)));
        }
    }
}
