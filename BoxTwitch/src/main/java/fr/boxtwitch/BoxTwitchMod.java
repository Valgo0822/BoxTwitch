package fr.boxtwitch;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BoxTwitchMod implements ModInitializer {
    public static final String MOD_ID = "boxtwitch";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playS2C().register(StatePayload.ID, StatePayload.CODEC);

        BoxManager m = BoxManager.I;
        ServerLifecycleEvents.SERVER_STARTED.register(m::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> m.stop());
        ServerTickEvents.END_SERVER_TICK.register(m::tick);
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> BoxCommands.register(dispatcher));

        // La coque en bedrock reste protégée même contre les joueurs en créatif.
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) ->
                !(world instanceof ServerWorld sw && m.isShell(sw, pos)));
    }
}
