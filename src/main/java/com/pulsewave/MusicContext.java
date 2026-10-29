package com.pulsewave;

import net.minecraft.sound.SoundEvent;

/** Every situation Pulsewave can play music for. {@code group} drives HYBRID transitions. */
public enum MusicContext {
    // ---- first column of the manager ----
    MENU("menu", "menu"),
    CREDITS("credits", "credits"),
    OVERWORLD_DAWN("overworld_dawn", "overworld"),
    OVERWORLD_DAY("overworld_day", "overworld"),
    OVERWORLD_DUSK("overworld_dusk", "overworld"),
    OVERWORLD_NIGHT("overworld_night", "overworld"),
    RAIN("rain", "weather"),
    WATER("water", "water"),
    CAVE("cave", "underground"),
    DEEP_DARK("deep_dark", "underground"),
    ANCIENT_CITY("ancient_city", "underground"),
    // ---- second column ----
    TRIAL_CHAMBERS("trial_chambers", "underground"),
    NETHER("nether", "nether"),
    END("end", "end"),
    CREATIVE("creative", "creative"),
    COMBAT_PVE("combat_pve", "combat"),
    COMBAT_INTENSE("combat_intense", "combat"),
    COMBAT_PVP("combat_pvp", "combat"),
    LOW_HEALTH("low_health", "tension"),
    BOSS_WITHER("boss_wither", "boss"),
    BOSS_ENDER_DRAGON("boss_ender_dragon", "boss"),
    BOSS_ELDER_GUARDIAN("boss_elder_guardian", "boss");

    public final String id;
    public final String group;
    private SoundEvent event;

    MusicContext(String id, String group) {
        this.id = id;
        this.group = group;
    }

    void bind(SoundEvent event) { this.event = event; }
    public SoundEvent event() { return event; }
    public boolean isBoss() { return group.equals("boss"); }
}
