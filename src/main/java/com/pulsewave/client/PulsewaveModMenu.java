package com.pulsewave.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Gives Pulsewave a "Configure" button in Mod Menu that opens the music manager. */
public class PulsewaveModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new PulsewaveMusicScreen(parent);
    }
}
