package com.terraformersmc.modmenu.api;

import net.minecraft.client.gui.screen.Screen;

/** Compile-time stub of Mod Menu's API. Excluded from the built jar (see build.gradle). */
@FunctionalInterface
public interface ConfigScreenFactory<S extends Screen> {
    S create(Screen parent);
}
