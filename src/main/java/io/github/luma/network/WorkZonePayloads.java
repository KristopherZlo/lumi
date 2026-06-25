package io.github.luma.network;

import io.github.luma.LumaMod;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class WorkZonePayloads {

    private static boolean registered;

    private WorkZonePayloads() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        PayloadTypeRegistry.playC2S().register(Request.TYPE, Request.CODEC);
        PayloadTypeRegistry.playS2C().register(Response.TYPE, Response.CODEC);
        registered = true;
    }

    public record Request(String action, String projectName, String zoneId, String zoneName, String tags) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<Request> TYPE = new CustomPacketPayload.Type<>(
                Identifier.fromNamespaceAndPath(LumaMod.MOD_ID, "work_zone_request")
        );
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(32),
                Request::action,
                ByteBufCodecs.stringUtf8(128),
                Request::projectName,
                ByteBufCodecs.stringUtf8(128),
                Request::zoneId,
                ByteBufCodecs.stringUtf8(128),
                Request::zoneName,
                ByteBufCodecs.stringUtf8(512),
                Request::tags,
                Request::new
        );

        public Request(String action, String projectName, String zoneId, String zoneName) {
            this(action, projectName, zoneId, zoneName, "");
        }

        public Request {
            action = action == null ? "" : action;
            projectName = projectName == null ? "" : projectName;
            zoneId = zoneId == null ? "" : zoneId;
            zoneName = zoneName == null ? "" : zoneName;
            tags = tags == null ? "" : tags;
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Response(String status, String json) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<Response> TYPE = new CustomPacketPayload.Type<>(
                Identifier.fromNamespaceAndPath(LumaMod.MOD_ID, "work_zone_response")
        );
        public static final StreamCodec<RegistryFriendlyByteBuf, Response> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64),
                Response::status,
                ByteBufCodecs.stringUtf8(1_048_576),
                Response::json,
                Response::new
        );

        public Response {
            status = status == null ? "" : status;
            json = json == null ? "" : json;
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
