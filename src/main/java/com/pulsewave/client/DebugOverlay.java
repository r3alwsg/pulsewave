package com.pulsewave.client;

import com.pulsewave.PulsewaveConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;

/** Small readout in the top-left corner: what is playing, what wants to play, and why things are skipped. */
final class DebugOverlay {
    private DebugOverlay() {}

    static void register() {
        HudElementRegistry.addLast(Identifier.of("pulsewave", "debug_overlay"),
                (ctx, tickCounter) -> render(ctx));
    }

    private static void render(DrawContext ctx) {
        if (!PulsewaveConfig.INSTANCE.debugOverlay) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        List<String> lines = MusicEngine.INSTANCE.debugLines();
        if (mc == null || lines.isEmpty()) return;

        TextRenderer tr = mc.textRenderer;
        int w = 0;
        for (String l : lines) w = Math.max(w, tr.getWidth(l));
        int x = 6, y = 6;
        ctx.fill(x - 3, y - 3, x + w + 3, y + lines.size() * 10 + 1, 0x99000000);
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            int color = i == 0 ? 0xFFFFFF55 : l.startsWith("  wants") ? 0xFFAAAAAA : 0xFFFFFFFF;
            ctx.drawTextWithShadow(tr, Text.literal(l), x, y + i * 10, color);
        }
    }
}
