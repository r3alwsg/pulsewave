# Pulsewave – Dynamic Contextual Music Engine (Fabric, Minecraft 1.21.11)

## Build
JDK 21. Loom 1.14 needs a recent Gradle: run `gradle wrapper --gradle-version latest` once, then `./gradlew build`
-> `build/libs/pulsewave-1.0.0.jar`. Needs Fabric Loader 0.18.1+ and Fabric API 0.141+ (1.21.11, Yarn mappings). Install on the client; also on the server for PVE/PVP signals.

## Adding music (in game)
Open the manager with **K** (rebindable), or the **Pulsewave Music** button on the title / pause screen.
1. Pick a folder on the left (Combat PVE, Combat PVP, Boss Wither, Cave, Rain, Menu, ...).
2. Click **Add .ogg...** (file picker) or drag files onto the window. **Open folder** opens it in your file browser.
3. **X** removes a track. Changes apply instantly, no resource reload.

Files are copied to `.minecraft/pulsewave_music/<context>/`. Several files per folder = random pick without
immediate repeats. A folder with no music is skipped (the next-best context plays instead).
Only **Ogg Vorbis (.ogg)** works; convert mp3/wav/flac first (e.g. Audacity or ffmpeg).
Track length is auto-detected from the file for the end-of-track fade.

## What runs where
- Client: context evaluation, transitions, ducking, boss scan, vanilla music suppression, the manager UI.
- Server: PVP hits (both players, team protection, timeout) and PVE (monsters targeting you). Works in
  singleplayer. Without the mod on the server, only client-evaluated contexts play.

## Config: `config/pulsewave.json`
transitionMode (CROSSFADE | GAP | HYBRID), fadeSeconds, gapSeconds, debounceSeconds, combatDuckVolume,
bossReleaseSeconds, pvpTimeoutSeconds, pvpTeamProtection, pveRange, pveHoldSeconds, ...

## Priority
overlay: Boss > PVP > PVE      base: Credits/Menu > Nether/End > Deep Dark > Cave > Rain > Dawn/Day/Dusk/Night

## Getting the .jar without installing anything
1. Create a free GitHub repo and upload the CONTENTS of this folder (including the hidden .github folder).
2. Open the Actions tab -> "build" -> wait ~3 minutes.
3. Download the `pulsewave-jar` artifact, unzip it, and put `pulsewave-1.0.0.jar` in `.minecraft/mods/` (with Fabric API).
