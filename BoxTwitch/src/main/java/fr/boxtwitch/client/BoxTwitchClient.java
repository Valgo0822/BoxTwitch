package fr.boxtwitch.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.boxtwitch.BoxTwitchMod;
import fr.boxtwitch.StatePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class BoxTwitchClient implements ClientModInitializer {
    public static volatile JsonObject state = new JsonObject();
    private static KeyBinding openKey;

    @Override
    public void onInitializeClient() {
        // 1.21.9+ : les touches ont une "catégorie" objet (traduction : key.category.boxtwitch.main)
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.boxtwitch.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B,
                KeyBinding.Category.create(Identifier.of(BoxTwitchMod.MOD_ID, "main"))));

        // La touche envoie simplement "/box" : le serveur vérifie les droits et renvoie l'ordre d'ouverture.
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openKey.wasPressed()) {
                if (mc.player != null) mc.player.networkHandler.sendChatCommand("box");
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(StatePayload.ID, (payload, ctx) -> {
            try {
                state = JsonParser.parseString(payload.json()).getAsJsonObject();
            } catch (Exception ignored) { return; }
            if (payload.open()) ctx.client().setScreen(new BoxScreen());
        });
    }
}
