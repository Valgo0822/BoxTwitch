package fr.boxtwitch;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Serveur -> client : état du défi (JSON) + demande éventuelle d'ouverture du menu. */
public record StatePayload(String json, boolean open) implements CustomPayload {
    public static final CustomPayload.Id<StatePayload> ID =
            new CustomPayload.Id<>(Identifier.of(BoxTwitchMod.MOD_ID, "state"));

    public static final PacketCodec<RegistryByteBuf, StatePayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, StatePayload::json,
            PacketCodecs.BOOLEAN, StatePayload::open,
            StatePayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
