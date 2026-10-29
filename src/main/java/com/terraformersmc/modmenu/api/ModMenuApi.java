package com.terraformersmc.modmenu.api;

/** Compile-time stub of Mod Menu's API. Excluded from the built jar (see build.gradle). */
public interface ModMenuApi {
    default ConfigScreenFactory<?> getModConfigScreenFactory() {
        return screen -> null;
    }
}
