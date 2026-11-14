package com.minecolonies.core.client.rts;

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.blueprints.v1.BlueprintTagUtils;
import com.ldtteam.structurize.client.gui.WindowExtendedBuildTool;
import com.ldtteam.structurize.storage.StructurePackMeta;
import com.ldtteam.structurize.storage.StructurePacks;
import com.ldtteam.structurize.storage.rendering.RenderingCache;
import com.ldtteam.structurize.storage.rendering.types.BlueprintPreviewData;
import com.minecolonies.api.colony.ICitizenDataView;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.IColonyView;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.minecolonies.api.configuration.RtsZonePlacementMode;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.MineColonies;
import com.minecolonies.core.Network;
import com.minecolonies.core.blocks.huts.BlockHutTownHall;
import com.minecolonies.core.client.gui.WindowBuildBuilding;
import com.minecolonies.core.client.gui.WindowInteraction;
import com.minecolonies.core.client.gui.citizen.MainWindowCitizen;
import com.minecolonies.core.network.messages.server.GetColonyInfoMessage;
import com.minecolonies.core.network.messages.server.RtsCitizenCommandMessage;
import com.minecolonies.core.network.messages.server.RtsZoneActionMessage;
import com.minecolonies.core.network.messages.server.SwitchBuildingWithToolMessage;
import com.minecolonies.core.network.messages.server.colony.building.BuildRequestMessage;
import com.minecolonies.core.rts.RtsResourceAggregator;
import com.minecolonies.core.rts.RtsZoneData;
import com.minecolonies.core.rts.RtsZoneType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

import static com.ldtteam.structurize.api.util.constant.Constants.GROUNDSTYLE_RELATIVE;

/**
 * Client-side RTS interaction bridge connecting 3rd-person raycast clicks and zone drags
 * to MineColonies' remote building GUIs, citizen GUIs, work orders, and Structurize blueprint previews.
 */
public final class RtsInteractionBridge
{
    private RtsInteractionBridge()
    {
    }

    /**
     * Submits a zone drag selection to the server and, if in Mode B, opens the blueprint preview GUI.
     */
    public static void submitZoneSelection(
        @NotNull final BlockPos startPos,
        @NotNull final BlockPos endPos,
        @NotNull final RtsZoneType zoneType,
        final boolean deleteMode,
        @NotNull final RtsZonePlacementMode placementMode)
    {
        Network.getNetwork().sendToServer(new RtsZoneActionMessage(startPos, endPos, zoneType, deleteMode, placementMode));

        if (!deleteMode && placementMode == RtsZonePlacementMode.MODE_B_BLUEPRINT_PREVIEW)
        {
            final Minecraft mc = Minecraft.getInstance();
            final IColonyView colonyView = getActiveClientColonyView(mc);
            if (colonyView == null && RtsResourceAggregator.getClientSnapshot().colonyId() <= 0)
            {
                if (mc.player != null)
                {
                    mc.gui.setOverlayMessage(
                        Component.literal("[RTS] 정착지(Colony)를 먼저 설립해야 구역을 지정할 수 있습니다. 시청(Town Hall)을 설치/우클릭하세요."),
                        false
                    );
                }
                return;
            }

            final BlockPos center = new BlockPos(
                (startPos.getX() + endPos.getX()) / 2,
                Math.max(startPos.getY(), endPos.getY()) + 1,
                (startPos.getZ() + endPos.getZ()) / 2
            );
            openModeBBlueprintPreview(center, zoneType);
        }
    }

    /**
     * Opens the Mode B detailed blueprint preview / style & rotation adjustment window at the target position.
     */
    public static void openModeBBlueprintPreview(@NotNull final BlockPos targetPos, @NotNull final RtsZoneType zoneType)
    {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null)
        {
            return;
        }

        final IBuildingView existingBuilding = RtsMousePicker.pickBuildingView(mc, targetPos);
        if (existingBuilding != null && existingBuilding.getColony() != null)
        {
            new WindowBuildBuilding(existingBuilding.getColony(), existingBuilding).open();
            mc.gui.setOverlayMessage(Component.literal("[RTS 방식 B] 건물 청사진 상세 조정 창 열기"), false);
            return;
        }

        final IColonyView colonyView = getActiveClientColonyView(mc);
        final String preferredPack = (colonyView != null && !colonyView.getStructurePack().isEmpty())
            ? colonyView.getStructurePack()
            : "Caledonia";

        try
        {
            if (StructurePacks.hasPack(preferredPack))
            {
                StructurePacks.switchSelectedPack(preferredPack);
            }
            else
            {
                StructurePacks.ensureSelectedPack();
            }
        }
        catch (final Throwable ignored)
        {
        }

        final StructurePackMeta selectedPack;
        String resolvedPack = preferredPack;
        try
        {
            selectedPack = StructurePacks.getSelectedPack();
            if (selectedPack != null && selectedPack.getName() != null && !selectedPack.getName().isEmpty())
            {
                resolvedPack = selectedPack.getName();
            }
        }
        catch (final Throwable ignored)
        {
        }

        final Block hutBlock = zoneType.getDefaultHutBlock();
        final WindowExtendedBuildTool window = new WindowExtendedBuildTool(targetPos, GROUNDSTYLE_RELATIVE);

        try
        {
            final BlueprintPreviewData previewData = RenderingCache.getOrCreateBlueprintPreviewData("blueprint");
            previewData.setPos(targetPos);
            if (hutBlock != null)
            {
                Blueprint matched = null;
                if (StructurePacks.hasPack(resolvedPack))
                {
                    matched = StructurePacks.findBlueprint(
                        resolvedPack,
                        bp -> !BlueprintTagUtils.isInvisible(bp)
                            && bp.getBlockState(bp.getPrimaryBlockOffset()).getBlock() == hutBlock
                            && bp.getFileName().endsWith("1.blueprint")
                    );
                    if (matched == null)
                    {
                        matched = StructurePacks.findBlueprint(
                            resolvedPack,
                            bp -> !BlueprintTagUtils.isInvisible(bp)
                                && bp.getBlockState(bp.getPrimaryBlockOffset()).getBlock() == hutBlock
                        );
                    }
                }
                if (matched != null)
                {
                    previewData.setBlueprint(matched);
                    previewData.syncChangesToServer();
                    window.selectedBlueprint = matched;
                }
                else
                {
                    previewData.setBlueprintFuture(StructurePacks.findBlueprintFuture(
                        resolvedPack,
                        bp -> !BlueprintTagUtils.isInvisible(bp)
                            && bp.getBlockState(bp.getPrimaryBlockOffset()).getBlock() == hutBlock
                    ));
                }
            }
        }
        catch (final Throwable ignored)
        {
        }

        boolean missingHutInSurvival = false;
        if (hutBlock != null)
        {
            final ItemStack hutStack = new ItemStack(hutBlock);
            Network.getNetwork().sendToServer(new SwitchBuildingWithToolMessage(hutStack));
            if (!mc.player.isCreative() && !playerHasItem(mc, hutBlock.asItem()))
            {
                missingHutInSurvival = true;
            }
        }

        window.open();
        final String hint = missingHutInSurvival
            ? " (서바이벌 배치 시 해당 오두막 아이템 필요)"
            : "";
        mc.gui.setOverlayMessage(
            Component.literal("[RTS 방식 B] " + zoneType.getDisplayName() + " 청사진 상세 배치 창 열기" + hint),
            false
        );
    }

    private static boolean playerHasItem(@NotNull final Minecraft mc, @NotNull final Item item)
    {
        if (mc.player == null)
        {
            return false;
        }
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++)
        {
            if (mc.player.getInventory().getItem(i).is(item))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Opens a MineColonies building GUI remotely without requiring the player to walk to the hut block.
     */
    public static void openRemoteBuildingGui(@NotNull final IBuildingView buildingView)
    {
        buildingView.openGui(false);
    }

    /**
     * Sends a remote build/upgrade work order request for a MineColonies building.
     */
    public static void requestRemoteBuildingUpgrade(@NotNull final IBuildingView buildingView)
    {
        Network.getNetwork().sendToServer(
            new BuildRequestMessage(buildingView, BuildRequestMessage.Mode.BUILD, BlockPos.ZERO)
        );
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player != null)
        {
            mc.gui.setOverlayMessage(
                Component.literal("[RTS] 원격 건설/업그레이드 작업 지시 전송 (Lv." + buildingView.getBuildingLevel() + ")"),
                false
            );
        }
    }

    /**
     * Opens a MineColonies citizen status/interaction window remotely.
     */
    public static void openRemoteCitizenGui(@NotNull final AbstractEntityCitizen citizen, final boolean openMainDashboard)
    {
        final ICitizenDataView citizenView = citizen.getCitizenDataView();
        if (citizenView == null)
        {
            return;
        }
        if (openMainDashboard)
        {
            new MainWindowCitizen(citizenView).open();
        }
        else
        {
            new WindowInteraction(citizenView).open();
        }
    }

    /**
     * Handles right-click in RTS camera mode:
     * 1. If citizens are selected -> commands them to move to the clicked location or work at the clicked building/zone.
     * 2. If no citizens are selected:
     *    - Right-clicking a citizen -> opens remote citizen GUI.
     *    - Right-clicking a building -> Shift+Right-Click requests upgrade, normal Right-Click opens remote building GUI
     *      (or if Alt is held & hybrid right-click is enabled, opens Mode B blueprint preview).
     *    - Right-clicking an un-founded Town Hall block -> requests colony info / opens founding covenant GUI.
     *    - Right-clicking an existing RTS zone -> if hybrid right-click is enabled, opens Mode B blueprint preview
     *      (or linked building GUI).
     */
    public static void handleRightClickInRts(
        @NotNull final Minecraft mc,
        @NotNull final Set<Integer> selectedCitizenEntityIds,
        @NotNull final RtsZoneType activeZoneType,
        @Nullable final String activeJobIdOverride,
        final boolean shiftDown,
        final boolean altDown)
    {
        if (mc.level == null || mc.player == null)
        {
            return;
        }

        final EntityHitResult entityHit = RtsMousePicker.pickEntity(mc, 160.0D);
        final BlockHitResult blockHit = RtsMousePicker.pickBlock(mc, 160.0D);
        final BlockPos hitPos = (blockHit != null && blockHit.getType() == HitResult.Type.BLOCK)
            ? blockHit.getBlockPos()
            : null;

        // Case 1: Selected citizens -> issue movement / job assignment command
        if (!selectedCitizenEntityIds.isEmpty())
        {
            if (hitPos != null)
            {
                final IBuildingView targetBuilding = RtsMousePicker.pickBuildingView(mc, hitPos);
                final RtsZoneData clickedZone = findZoneContaining(hitPos);
                String jobToAssign = activeJobIdOverride;
                BlockPos commandPos = hitPos.above();

                if (targetBuilding != null)
                {
                    commandPos = targetBuilding.getPosition();
                }
                else if (clickedZone != null)
                {
                    if (jobToAssign == null || jobToAssign.isEmpty())
                    {
                        final net.minecraft.resources.ResourceLocation defaultJob = clickedZone.type().getDefaultJobId();
                        jobToAssign = defaultJob != null ? defaultJob.getPath() : null;
                    }
                    if (clickedZone.linkedBuildingPos() != null)
                    {
                        commandPos = clickedZone.linkedBuildingPos();
                    }
                }

                final int colonyId = RtsResourceAggregator.getClientSnapshot().colonyId();
                Network.getNetwork().sendToServer(new RtsCitizenCommandMessage(
                    colonyId,
                    List.copyOf(selectedCitizenEntityIds),
                    commandPos,
                    jobToAssign
                ));
                mc.gui.setOverlayMessage(
                    Component.literal("[RTS] 선택된 시민 " + selectedCitizenEntityIds.size() + "명 이동/배치 명령 전송"),
                    false
                );
            }
            return;
        }

        // Case 2: Right-clicked directly on a MineColonies citizen -> open remote citizen GUI
        if (entityHit != null)
        {
            final Entity entity = entityHit.getEntity();
            if (entity instanceof AbstractEntityCitizen citizen)
            {
                openRemoteCitizenGui(citizen, !shiftDown);
                return;
            }
        }

        if (hitPos == null)
        {
            return;
        }

        final boolean hybridModeBEnabled = MineColonies.getConfig().getClient().rtsHybridRightClickModeB.get();
        final RtsZoneData clickedZone = findZoneContaining(hitPos);

        // Case 3: Right-clicked on a building (or a zone's linked building)
        IBuildingView buildingView = RtsMousePicker.pickBuildingView(mc, hitPos);
        if (buildingView == null && clickedZone != null && clickedZone.linkedBuildingPos() != null)
        {
            buildingView = RtsMousePicker.pickBuildingView(mc, clickedZone.linkedBuildingPos());
        }

        if (buildingView != null)
        {
            if (altDown && hybridModeBEnabled)
            {
                new WindowBuildBuilding(buildingView.getColony(), buildingView).open();
                return;
            }
            if (shiftDown)
            {
                requestRemoteBuildingUpgrade(buildingView);
            }
            else
            {
                openRemoteBuildingGui(buildingView);
            }
            return;
        }

        // Case 3.5: Un-founded Town Hall block (before colony exists or outside known view)
        if (mc.level.getBlockState(hitPos).getBlock() instanceof BlockHutTownHall)
        {
            if (System.currentTimeMillis() > BlockHutTownHall.timeout)
            {
                Network.getNetwork().sendToServer(new GetColonyInfoMessage(hitPos));
                BlockHutTownHall.timeout = System.currentTimeMillis() + 1000L;
                mc.gui.setOverlayMessage(Component.literal("[RTS] 시청 정보 및 정착지 설립 창 열기"), false);
            }
            return;
        }

        // Case 4: Hybrid Mode B right-click on an existing RTS zone (or ground with Alt held)
        if (hybridModeBEnabled && (clickedZone != null || altDown))
        {
            final RtsZoneType previewType = clickedZone != null ? clickedZone.type() : activeZoneType;
            final BlockPos previewPos = (clickedZone != null && clickedZone.linkedBuildingPos() != null)
                ? clickedZone.linkedBuildingPos()
                : hitPos.above();
            openModeBBlueprintPreview(previewPos, previewType);
        }
    }

    @Nullable
    public static RtsZoneData findZoneContaining(@NotNull final BlockPos pos)
    {
        for (final RtsZoneData zone : RtsResourceAggregator.getClientSnapshot().zones())
        {
            if (zone.bounds().isInside(pos) || zone.bounds().inflatedBy(1).isInside(pos))
            {
                return zone;
            }
        }
        return null;
    }

    @Nullable
    public static IColonyView getActiveClientColonyView(@NotNull final Minecraft mc)
    {
        if (mc.level == null || mc.player == null)
        {
            return null;
        }
        return IColonyManager.getInstance().getClosestColonyView(mc.level, mc.player.blockPosition());
    }
}
