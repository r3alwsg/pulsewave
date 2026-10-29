package com.pulsewave.client;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;

/** A square vanilla-style button with the Pulsewave logo drawn inside it. */
final class LogoButton extends ButtonWidget {
    LogoButton(int x, int y, int size, PressAction onPress) {
        super(x, y, size, size, net.minecraft.text.Text.literal(""), onPress, DEFAULT_NARRATION_SUPPLIER);
    }

    /** The button background is drawn by vanilla; this is the slot for the icon on top of it. */
    @Override
    public void drawIcon(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, PulsewaveMusicScreen.LOGO,
                getX() + 2, getY() + 2, 0f, 0f, getWidth() - 4, getHeight() - 4, 256, 256, 256, 256);
    }
}
