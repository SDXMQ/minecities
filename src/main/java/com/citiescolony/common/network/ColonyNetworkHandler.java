package com.citiescolony.common.network;

import com.citiescolony.api.ZoneData;
import com.citiescolony.common.city.CityZoneManager;
import com.citiescolony.common.city.StorageLogisticsHelper;
import com.citiescolony.common.entity.ColonyCitizenEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.List;
import java.util.function.Consumer;

/**
 * Registers and handles all 5 CustomPacketPayload packets for Minecraft Cities Colony.
 * Strictly separates client-side handlers via functional listeners so no client classes are imported here.
 */
public final class ColonyNetworkHandler {

    public static final String PROTOCOL_VERSION = "1.0";
    public static final double MAX_RTS_INTERACTION_DISTANCE_SQR = 256.0D * 256.0D;
    public static final int MAX_ZONE_SPAN = 96;
    public static final int MAX_COMMANDED_CITIZENS = 64;

    private static volatile Consumer<CitySyncPayload> clientCitySyncConsumer = payload -> {};
    private static volatile Consumer<ResourceHudSyncPayload> clientResourceHudConsumer = payload -> {};

    private ColonyNetworkHandler() {
    }

    public static void registerClientReceivers(
            Consumer<CitySyncPayload> citySyncConsumer,
            Consumer<ResourceHudSyncPayload> resourceHudConsumer
    ) {
        if (citySyncConsumer != null) {
            clientCitySyncConsumer = citySyncConsumer;
        }
        if (resourceHudConsumer != null) {
            clientResourceHudConsumer = resourceHudConsumer;
        }
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);

        registrar.playToServer(
                ZoneActionPayload.TYPE,
                ZoneActionPayload.STREAM_CODEC,
                ColonyNetworkHandler::handleZoneActionServer
        );

        registrar.playToServer(
                CitizenCommandPayload.TYPE,
                CitizenCommandPayload.STREAM_CODEC,
                ColonyNetworkHandler::handleCitizenCommandServer
        );

        registrar.playToServer(
                JobAssignPayload.TYPE,
                JobAssignPayload.STREAM_CODEC,
                ColonyNetworkHandler::handleJobAssignServer
        );

        registrar.playToClient(
                CitySyncPayload.TYPE,
                CitySyncPayload.STREAM_CODEC,
                ColonyNetworkHandler::handleCitySyncClient
        );

        registrar.playToClient(
                ResourceHudSyncPayload.TYPE,
                ResourceHudSyncPayload.STREAM_CODEC,
                ColonyNetworkHandler::handleResourceHudSyncClient
        );
    }

    public static void handleZoneActionServer(ZoneActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!isAuthorizedForRtsAction(player, payload.startPos())
                    || !isAuthorizedForRtsAction(player, payload.endPos())) {
                return;
            }
            BoundingBox bounds = BoundingBox.fromCorners(payload.startPos(), payload.endPos());
            if (bounds.getXSpan() > MAX_ZONE_SPAN
                    || bounds.getYSpan() > MAX_ZONE_SPAN
                    || bounds.getZSpan() > MAX_ZONE_SPAN) {
                return;
            }

            ServerLevel level = player.serverLevel();
            CityZoneManager manager = CityZoneManager.get(level);
            if (payload.deleteMode()) {
                manager.removeZonesIntersecting(bounds);
            } else {
                manager.createZone(payload.zoneType(), payload.startPos(), payload.endPos());
            }
            StorageLogisticsHelper.refreshStorageCache(level, manager);
            broadcastCitySync(level, manager);
            broadcastResourceHudSync(level, manager);
        });
    }

    public static void handleCitizenCommandServer(CitizenCommandPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            BlockPos targetPos = payload.targetPos();
            if (!isAuthorizedForRtsAction(player, targetPos)) {
                return;
            }

            ServerLevel level = player.serverLevel();
            List<Integer> ids = payload.citizenEntityIds();
            int limit = Math.min(ids.size(), MAX_COMMANDED_CITIZENS);

            for (int i = 0; i < limit; i++) {
                Entity entity = level.getEntity(ids.get(i));
                if (entity instanceof ColonyCitizenEntity citizen
                        && citizen.isAlive()
                        && citizen.distanceToSqr(player) <= MAX_RTS_INTERACTION_DISTANCE_SQR) {
                    citizen.issuePlayerCommand(level, targetPos);
                }
            }
            CityZoneManager manager = CityZoneManager.get(level);
            broadcastCitySync(level, manager);
        });
    }

    public static void handleJobAssignServer(JobAssignPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!player.isAlive() || player.isSpectator()) {
                return;
            }

            ServerLevel level = player.serverLevel();
            Entity entity = level.getEntity(payload.citizenEntityId());
            if (entity instanceof ColonyCitizenEntity citizen
                    && citizen.isAlive()
                    && citizen.distanceToSqr(player) <= MAX_RTS_INTERACTION_DISTANCE_SQR) {
                citizen.setJobType(payload.jobType());
                CityZoneManager manager = CityZoneManager.get(level);
                broadcastResourceHudSync(level, manager);
            }
        });
    }

    private static void handleCitySyncClient(CitySyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> clientCitySyncConsumer.accept(payload));
    }

    private static void handleResourceHudSyncClient(ResourceHudSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> clientResourceHudConsumer.accept(payload));
    }

    public static boolean isAuthorizedForRtsAction(ServerPlayer player, BlockPos targetPos) {
        if (player == null || targetPos == null || !player.isAlive() || player.isSpectator()) {
            return false;
        }
        if (!player.serverLevel().isLoaded(targetPos)) {
            return false;
        }
        return player.blockPosition().distSqr(targetPos) <= MAX_RTS_INTERACTION_DISTANCE_SQR;
    }

    public static void sendCitySyncToPlayer(ServerPlayer player, CityZoneManager manager) {
        CitySyncPayload payload = new CitySyncPayload(manager.getZoneList(), manager.getTownHallPos());
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void broadcastCitySync(ServerLevel level, CityZoneManager manager) {
        List<ZoneData> zones = manager.getZoneList();
        CitySyncPayload payload = new CitySyncPayload(zones, manager.getTownHallPos());
        PacketDistributor.sendToPlayersInDimension(level, payload);
    }

    public static void broadcastResourceHudSync(ServerLevel level, CityZoneManager manager) {
        StorageLogisticsHelper.ResourceSummary resources = StorageLogisticsHelper.computeResourceSummary(level, manager);
        int totalCitizens = 0;
        int idleCitizens = 0;

        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof ColonyCitizenEntity citizen && citizen.isAlive()) {
                totalCitizens++;
                if (citizen.isCitizenIdle()) {
                    idleCitizens++;
                }
            }
        }

        ResourceHudSyncPayload payload = new ResourceHudSyncPayload(
                resources.food(),
                resources.wood(),
                resources.stone(),
                resources.minerals(),
                totalCitizens,
                idleCitizens
        );
        PacketDistributor.sendToPlayersInDimension(level, payload);
    }
}
