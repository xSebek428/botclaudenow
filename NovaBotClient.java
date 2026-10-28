package pl.novabot;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * Entrypoint "client": wczytuje config, rejestruje klawisz menu (B)
 * i dodaje przycisk "NovaBot" na ekranie tytułowym.
 */
public class NovaBotClient implements ClientModInitializer {
    public static KeyBinding openKey;

    @Override
    public void onInitializeClient() {
        BotConfig.load();

        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.novabot.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, "category.novabot"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.wasPressed()) {
                if (client.currentScreen == null) client.setScreen(new BotScreen(null));
            }
        });

        // Przycisk w lewym górnym rogu menu głównego - bota można włączyć jeszcze przed wejściem do gry.
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof TitleScreen) {
                Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal("NovaBot"),
                        b -> client.setScreen(new BotScreen(screen))).dimensions(4, 4, 56, 20).build());
            }
        });
    }
}
