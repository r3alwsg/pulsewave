package com.pulsewave.client;

import com.pulsewave.CombatSignalPayload;
import com.pulsewave.PulsewaveConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public class PulsewaveClient implements ClientModInitializer {
    private static final int ICON = 20;

    @Override
    public void onInitializeClient() {
        UserMusic.init();

        ClientPlayNetworking.registerGlobalReceiver(CombatSignalPayload.ID,
                (payload, context) -> MusicEngine.INSTANCE.onSignal(payload));
        ClientTickEvents.END_CLIENT_TICK.register(MusicEngine.INSTANCE::tick);

        // Client-side combat detection: you attack a player or a monster.
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient()) MusicEngine.INSTANCE.onLocalAttack(player, entity);
            return ActionResult.PASS;
        });

        // Hotkey (default K) to open the manager anywhere.
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("pulsewave", "main"));
        KeyBinding openKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.pulsewave.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, category));
        KeyBinding debugKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.pulsewave.debug", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F6, category));
        DebugOverlay.register();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (debugKey.wasPressed()) {
                PulsewaveConfig.INSTANCE.debugOverlay = !PulsewaveConfig.INSTANCE.debugOverlay;
                PulsewaveConfig.save();
            }
            while (openKey.wasPressed()) {
                if (client.currentScreen == null) client.setScreen(new PulsewaveMusicScreen(null));
            }
        });

        // A small logo button that sits in the bottom row of the title screen and the pause menu,
        // right next to the vanilla buttons (like the accessibility / language icons).
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof TitleScreen || screen instanceof GameMenuScreen)) return;

            List<ClickableWidget> widgets = Screens.getButtons(screen);
            int[] slot = findSlot(widgets, w);

            ButtonWidget button = new LogoButton(slot[0], slot[1], ICON,
                    b -> client.setScreen(new PulsewaveMusicScreen(screen)));
            button.setTooltip(Tooltip.of(Text.literal("Pulsewave Music Manager")));
            widgets.add(button);
        });
    }

    /** Right of the last standard-size button in the bottom row; top-left corner if there's no room. */
    private static int[] findSlot(List<ClickableWidget> widgets, int screenWidth) {
        int bottom = Integer.MIN_VALUE;
        for (ClickableWidget b : widgets) {
            if (b.getHeight() == 20 && b.getWidth() >= 20) bottom = Math.max(bottom, b.getY());
        }
        if (bottom == Integer.MIN_VALUE) return new int[]{4, 4};

        int right = Integer.MIN_VALUE;
        for (ClickableWidget b : widgets) {
            if (b.getHeight() == 20 && b.getWidth() >= 20 && b.getY() == bottom) {
                right = Math.max(right, b.getX() + b.getWidth());
            }
        }
        int x = right + 4;
        if (x + ICON > screenWidth - 2) return new int[]{4, 4};
        return new int[]{x, bottom};
    }
}
