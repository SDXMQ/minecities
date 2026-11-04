package com.minecolonies.core.client.rts;

import com.minecolonies.api.colony.IColonyView;
import com.minecolonies.core.rts.RtsResourceAggregator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Top-screen Cities-like RTS HUD overlay displaying colony resources, citizen metrics, happiness,
 * active zone designation mode, and building placement mode (Mode A / Mode B).
 */
public final class RtsTopBarOverlay
{
    public static final IGuiOverlay OVERLAY = RtsTopBarOverlay::renderOverlay;

    private RtsTopBarOverlay()
    {
    }

    public static void onRegisterGuiOverlays(@NotNull final RegisterGuiOverlaysEvent event)
    {
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "rts_top_bar", OVERLAY);
    }

    private static void renderOverlay(
        final ForgeGui gui,
        final GuiGraphics graphics,
        final float partialTick,
        final int screenWidth,
        final int screenHeight)
    {
        if (!RtsCameraController.isActive())
        {
            return;
        }

        final Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui)
        {
            return;
        }

        final RtsResourceAggregator.ColonySnapshot snap = RtsResourceAggregator.getClientSnapshot();
        final IColonyView colonyView = RtsInteractionBridge.getActiveClientColonyView(mc);

        final int totalCitizens = snap.totalCitizens() > 0 || colonyView == null
            ? snap.totalCitizens()
            : colonyView.getCitizens().size();
        final int maxCitizens = snap.maxCitizens() > 0 || colonyView == null
            ? snap.maxCitizens()
            : colonyView.getCitizenCountLimit();
        final double happiness = snap.colonyId() > 0 || colonyView == null
            ? snap.overallHappiness()
            : colonyView.getOverallHappiness();

        // Top Bar Background & Accent Line
        graphics.fill(0, 0, screenWidth, 34, 0xCC12161F);
        graphics.fill(0, 34, screenWidth, 36, 0xFF3A7CA5);

        final String modeLabel = RtsCameraController.isDeleteMode()
            ? "§c[X:철거모드]"
            : "§a[Z:" + RtsCameraController.getActiveZoneType().getDisplayName() + "]";

        final String placementLabel = "§b[B:" + RtsCameraController.getActivePlacementMode().getDisplayName() + "]";

        final String line1 = String.format(
            Locale.ROOT,
            "§6[MineColonies RTS] §f식량: §e%d §7| §f목재: §a%d §7| §f석재: §7%d §7| §f광물: §b%d §7| §f시민: §d%d/%d §7(유휴:%d) §7| §f행복도: §6%.1f/10",
            snap.food(),
            snap.wood(),
            snap.stone(),
            snap.minerals(),
            totalCitizens,
            maxCitizens,
            snap.idleCitizens(),
            happiness
        );

        final String line2 = String.format(
            Locale.ROOT,
            "%s  %s  §f[J:직업: §e%s§f]  §7(고도: %.0fm | 선택시민: %d명 | 우클릭: 원격GUI / 구역상세배치[방식B] | F4: 복귀)",
            modeLabel,
            placementLabel,
            RtsCameraController.getActiveJobDisplayLabel(),
            RtsCameraController.getAltitude(),
            RtsCameraController.getSelectedCitizens().size()
        );

        graphics.drawCenteredString(mc.font, line1, screenWidth / 2, 6, 0xFFFFFF);
        graphics.drawCenteredString(mc.font, line2, screenWidth / 2, 19, 0xE0E0E0);
    }
}
