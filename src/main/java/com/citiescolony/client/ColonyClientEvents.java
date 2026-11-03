package com.citiescolony.client;

import com.citiescolony.CitiesColonyMod;
import com.citiescolony.common.network.CitizenCommandPayload;
import com.citiescolony.common.network.ColonyNetworkHandler;
import com.citiescolony.common.network.JobAssignPayload;
import com.citiescolony.common.network.ZoneActionPayload;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-only event subscriber wiring RTS camera, Blaze3D renderer, 26.2 HUD layer, and network dispatchers.
 */
@EventBusSubscriber(modid = CitiesColonyMod.MOD_ID, value = Dist.CLIENT)
public final class ColonyClientEvents {

    private static final Identifier CITIZEN_SKIN_TEXTURE =
            Identifier.withDefaultNamespace("textures/entity/steve.png");

    private ColonyClientEvents() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            RtsCameraController.setNetworkDispatchers(
                    (startPos, endPos, zoneType, deleteMode) ->
                            PacketDistributor.sendToServer(new ZoneActionPayload(startPos, endPos, zoneType, deleteMode)),
                    (citizenIds, targetPos) ->
                            PacketDistributor.sendToServer(new CitizenCommandPayload(citizenIds, targetPos)),
                    (citizenId, jobType) ->
                            PacketDistributor.sendToServer(new JobAssignPayload(citizenId, jobType))
            );

            ColonyNetworkHandler.registerClientReceivers(
                    citySync -> ZoneGridBlazeRenderer.updateClientZones(citySync.zones()),
                    hudSync -> RtsTopBarHudOverlay.updateHudStats(
                            hudSync.food(),
                            hudSync.wood(),
                            hudSync.stone(),
                            hudSync.minerals(),
                            hudSync.totalCitizens(),
                            hudSync.idleCitizens()
                    )
            );
        });
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        RtsKeyMappings.onRegisterKeyMappings(event);
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        RtsTopBarHudOverlay.onRegisterGuiLayers(event);
    }

    @SubscribeEvent
    public static void onRegisterEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(
                CitiesColonyMod.COLONY_CITIZEN_ENTITY.get(),
                (EntityRendererProvider.Context ctx) -> new HumanoidMobRenderer<>(
                        ctx,
                        new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER)),
                        0.5F
                ) {
                    @Override
                    public Identifier getTextureLocation(HumanoidRenderState renderState) {
                        return CITIZEN_SKIN_TEXTURE;
                    }

                    @Override
                    public HumanoidRenderState createRenderState() {
                        return new HumanoidRenderState();
                    }
                }
        );
    }

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        RtsCameraController.onClientTick();
    }

    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        RtsCameraController.onComputeCameraAngles(event);
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        RtsCameraController.onMouseScroll(event);
    }

    @SubscribeEvent
    public static void onMouseButtonPre(InputEvent.MouseButton.Pre event) {
        RtsCameraController.onMouseButtonPre(event);
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        ZoneGridBlazeRenderer.onRenderLevelStage(event);
    }
}
