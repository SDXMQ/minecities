package com.minecolonies.core.client.rts;

import com.minecolonies.api.colony.IColonyView;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.minecolonies.api.configuration.RtsZonePlacementMode;
import com.minecolonies.core.rts.RtsResourceAggregator;
import com.minecolonies.core.rts.RtsZoneData;
import com.minecolonies.core.rts.RtsZoneType;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.util.Lazy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Top-screen Cities-like RTS HUD overlay and interactive bottom toolbar displaying colony resources,
 * citizen metrics, happiness, active zone/job/placement modes, and hovered building/zone context.
 */
public final class RtsTopBarOverlay
{
    public static final IGuiOverlay OVERLAY = RtsTopBarOverlay::renderOverlay;
    public static final int TOP_BAR_HEIGHT = 29;
    public static final int TOOLBAR_BUTTON_HEIGHT = 15;

    public enum ToolbarActionType
    {
        ZONE_TYPE,
        TOGGLE_DELETE,
        CYCLE_PLACEMENT_MODE,
        CYCLE_CITIZEN_JOB
    }

    public record ToolbarButton(
        @NotNull ToolbarActionType actionType,
        @Nullable RtsZoneType zoneType,
        @NotNull String label,
        int x,
        int y,
        int width,
        int height,
        int accentArgb
    )
    {
        public boolean contains(final double guiX, final double guiY)
        {
            return guiX >= x && guiX < (x + width) && guiY >= y && guiY < (y + height);
        }
    }

    private RtsTopBarOverlay()
    {
    }

    public static void onRegisterGuiOverlays(@NotNull final RegisterGuiOverlaysEvent event)
    {
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "rts_top_bar", OVERLAY);
    }

    public static String formatResourceSummaryLine(
        final int food,
        final int wood,
        final int stone,
        final int minerals,
        final int totalCitizens,
        final int maxCitizens,
        final int idleCitizens,
        final double happiness)
    {
        return String.format(
            Locale.ROOT,
            "식량: %d | 목재: %d | 석재: %d | 광물: %d | 시민: %d/%d (유휴:%d) | 행복도: %.1f/10",
            food, wood, stone, minerals, totalCitizens, maxCitizens, idleCitizens, happiness
        );
    }

    @NotNull
    public static String getZoneShortLabel(@NotNull final RtsZoneType type)
    {
        return switch (type)
        {
            case RESIDENTIAL -> "주거";
            case LOGGING -> "벌목";
            case MINING -> "채광";
            case FARMING -> "농업";
            case STORAGE -> "창고";
            case DEFENSE -> "방어";
            case BUILDER -> "건축";
        };
    }

    /**
     * Computes deterministic layout geometry for the interactive Cities-style RTS bottom toolbar.
     * Clears vanilla hotbar/status bars horizontally and vertically, and auto-wraps on narrow windows.
     */
    @NotNull
    public static List<ToolbarButton> computeToolbarButtons(
        final int screenWidth,
        final int screenHeight,
        @NotNull final RtsZonePlacementMode placementMode,
        @NotNull final String shortJobLabel)
    {
        final RtsZoneType[] zoneTypes = RtsZoneType.values();
        final int gap = 2;
        final int groupGap = 6;
        final boolean wide = screenWidth >= 420;
        final int zoneBtnW = wide ? 32 : 28;
        final int deleteBtnW = 32;
        final int modeBtnW = 40;
        final int jobBtnW = 46;

        final int zonesRowW = zoneTypes.length * zoneBtnW + (zoneTypes.length - 1) * gap;
        final int actionsRowW = deleteBtnW + gap + modeBtnW + gap + jobBtnW;
        final int combinedW = zonesRowW + groupGap + actionsRowW;
        final String modeText = placementMode == RtsZonePlacementMode.MODE_B_BLUEPRINT_PREVIEW ? "방식B" : "방식A";
        final String jobText = "직:" + (shortJobLabel.isEmpty() ? "기본" : shortJobLabel);

        final List<ToolbarButton> result = new ArrayList<>(zoneTypes.length + 3);
        final int bottomRowY = Math.max(TOP_BAR_HEIGHT + 16, screenHeight - 58);

        if (combinedW + 10 <= screenWidth)
        {
            int curX = Math.max(4, (screenWidth - combinedW) / 2);
            for (final RtsZoneType type : zoneTypes)
            {
                result.add(new ToolbarButton(
                    ToolbarActionType.ZONE_TYPE,
                    type,
                    getZoneShortLabel(type),
                    curX,
                    bottomRowY,
                    zoneBtnW,
                    TOOLBAR_BUTTON_HEIGHT,
                    type.argbColor(255)
                ));
                curX += zoneBtnW + gap;
            }
            curX += groupGap - gap;

            result.add(new ToolbarButton(
                ToolbarActionType.TOGGLE_DELETE,
                null,
                "철거",
                curX,
                bottomRowY,
                deleteBtnW,
                TOOLBAR_BUTTON_HEIGHT,
                0xFFE84848
            ));
            curX += deleteBtnW + gap;

            result.add(new ToolbarButton(
                ToolbarActionType.CYCLE_PLACEMENT_MODE,
                null,
                modeText,
                curX,
                bottomRowY,
                modeBtnW,
                TOOLBAR_BUTTON_HEIGHT,
                0xFF4FB3E8
            ));
            curX += modeBtnW + gap;

            result.add(new ToolbarButton(
                ToolbarActionType.CYCLE_CITIZEN_JOB,
                null,
                jobText,
                curX,
                bottomRowY,
                jobBtnW,
                TOOLBAR_BUTTON_HEIGHT,
                0xFFE8B94F
            ));
            return result;
        }

        // Compact 2-row fallback for very narrow screens (< 362px)
        final int effectiveZoneW = screenWidth < zonesRowW + 8
            ? Math.max(20, (screenWidth - 8 - (zoneTypes.length - 1) * gap) / zoneTypes.length)
            : zoneBtnW;
        final int effZonesW = zoneTypes.length * effectiveZoneW + (zoneTypes.length - 1) * gap;
        final int zoneRowY = Math.max(TOP_BAR_HEIGHT + 2, bottomRowY - TOOLBAR_BUTTON_HEIGHT - 2);
        int curX = Math.max(4, (screenWidth - effZonesW) / 2);
        for (final RtsZoneType type : zoneTypes)
        {
            result.add(new ToolbarButton(
                ToolbarActionType.ZONE_TYPE,
                type,
                getZoneShortLabel(type),
                curX,
                zoneRowY,
                effectiveZoneW,
                TOOLBAR_BUTTON_HEIGHT,
                type.argbColor(255)
            ));
            curX += effectiveZoneW + gap;
        }

        int actX = Math.max(4, (screenWidth - actionsRowW) / 2);
        result.add(new ToolbarButton(
            ToolbarActionType.TOGGLE_DELETE,
            null,
            "철거",
            actX,
            bottomRowY,
            deleteBtnW,
            TOOLBAR_BUTTON_HEIGHT,
            0xFFE84848
        ));
        actX += deleteBtnW + gap;
        result.add(new ToolbarButton(
            ToolbarActionType.CYCLE_PLACEMENT_MODE,
            null,
            modeText,
            actX,
            bottomRowY,
            modeBtnW,
            TOOLBAR_BUTTON_HEIGHT,
            0xFF4FB3E8
        ));
        actX += modeBtnW + gap;
        result.add(new ToolbarButton(
            ToolbarActionType.CYCLE_CITIZEN_JOB,
            null,
            jobText,
            actX,
            bottomRowY,
            jobBtnW,
            TOOLBAR_BUTTON_HEIGHT,
            0xFFE8B94F
        ));
        return result;
    }

    /**
     * Returns the padded bounding box {minX, minY, maxX, maxY} around the toolbar dock buttons.
     */
    @NotNull
    public static int[] computeToolbarDockBounds(@NotNull final List<ToolbarButton> buttons)
    {
        if (buttons.isEmpty())
        {
            return new int[]{0, 0, 0, 0};
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (final ToolbarButton btn : buttons)
        {
            minX = Math.min(minX, btn.x());
            minY = Math.min(minY, btn.y());
            maxX = Math.max(maxX, btn.x() + btn.width());
            maxY = Math.max(maxY, btn.y() + btn.height());
        }
        return new int[]{minX - 3, minY - 3, maxX + 3, maxY + 3};
    }

    /**
     * Returns the Y anchor for the vanilla chat overlay so chat lines render cleanly above both the RTS bottom dock and ActionBar.
     */
    public static int getPreferredChatBottomY(final int screenWidth, final int screenHeight)
    {
        final List<ToolbarButton> buttons = computeToolbarButtons(
            screenWidth,
            screenHeight,
            RtsZonePlacementMode.MODE_A_AUTO_PLACE,
            "기본"
        );
        final int[] dock = computeToolbarDockBounds(buttons);
        final int defaultChatY = Math.max(40, screenHeight - 40);
        if (dock[1] <= TOP_BAR_HEIGHT)
        {
            return defaultChatY;
        }
        final int aboveDockY = Math.max(TOP_BAR_HEIGHT + 24, dock[1] - 4);
        final int aboveActionBarY = Math.max(TOP_BAR_HEIGHT + 24, screenHeight - 75);
        return Math.min(defaultChatY, Math.min(aboveDockY, aboveActionBarY));
    }

    @Nullable
    public static ToolbarButton findToolbarButtonAt(
        final int screenWidth,
        final int screenHeight,
        @NotNull final RtsZonePlacementMode placementMode,
        @NotNull final String shortJobLabel,
        final double guiX,
        final double guiY)
    {
        for (final ToolbarButton btn : computeToolbarButtons(screenWidth, screenHeight, placementMode, shortJobLabel))
        {
            if (btn.contains(guiX, guiY))
            {
                return btn;
            }
        }
        return null;
    }

    public static boolean isHudBlockingPoint(
        @NotNull final List<ToolbarButton> buttons,
        final double guiX,
        final double guiY)
    {
        if (guiY >= 0.0D && guiY <= TOP_BAR_HEIGHT + 2)
        {
            return true;
        }
        if (!buttons.isEmpty())
        {
            final int[] dock = computeToolbarDockBounds(buttons);
            return guiX >= dock[0] && guiX <= dock[2] && guiY >= dock[1] && guiY <= dock[3];
        }
        return false;
    }

    /**
     * Handles left-click against the RTS toolbar/HUD before 3D world picking.
     * Returns true if a toolbar button or HUD backdrop consumed the click.
     */
    public static boolean handleToolbarClick(
        @NotNull final Minecraft mc,
        final double guiMouseX,
        final double guiMouseY)
    {
        final int screenWidth = mc.getWindow().getGuiScaledWidth();
        final int screenHeight = mc.getWindow().getGuiScaledHeight();
        final List<ToolbarButton> buttons = computeToolbarButtons(
            screenWidth,
            screenHeight,
            RtsCameraController.getActivePlacementMode(),
            RtsCameraController.getActiveJobShortLabel()
        );

        for (final ToolbarButton btn : buttons)
        {
            if (btn.contains(guiMouseX, guiMouseY))
            {
                switch (btn.actionType())
                {
                    case ZONE_TYPE ->
                    {
                        if (btn.zoneType() != null)
                        {
                            RtsCameraController.setActiveZoneType(btn.zoneType(), mc);
                        }
                    }
                    case TOGGLE_DELETE -> RtsCameraController.toggleDeleteMode(mc);
                    case CYCLE_PLACEMENT_MODE -> RtsCameraController.cyclePlacementMode(mc);
                    case CYCLE_CITIZEN_JOB -> RtsCameraController.cycleCitizenJob(mc);
                }
                return true;
            }
        }

        return isHudBlockingPoint(buttons, guiMouseX, guiMouseY);
    }

    @NotNull
    private static String safeKeyName(@NotNull final Lazy<KeyMapping> mapping, @NotNull final String fallback)
    {
        try
        {
            final String s = mapping.get().getTranslatedKeyMessage().getString();
            return (s == null || s.isEmpty()) ? fallback : s.toUpperCase(Locale.ROOT);
        }
        catch (final Throwable ignored)
        {
            return fallback;
        }
    }

    private static void drawCenteredScaledString(
        @NotNull final GuiGraphics graphics,
        @NotNull final Font font,
        @NotNull final String text,
        final int centerX,
        final int y,
        final int maxWidth,
        final int color)
    {
        final int textWidth = font.width(text);
        if (textWidth <= maxWidth || textWidth <= 0)
        {
            graphics.drawCenteredString(font, text, centerX, y, color);
            return;
        }

        final float scale = Math.max(0.50F, (float) maxWidth / (float) textWidth);
        final PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(centerX, y, 0.0F);
        pose.scale(scale, scale, 1.0F);
        graphics.drawString(font, text, -textWidth / 2, 0, color, true);
        pose.popPose();
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
        if (mc.options.hideGui || mc.screen != null)
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
        graphics.fill(0, 0, screenWidth, TOP_BAR_HEIGHT, 0xCC12161F);
        graphics.fill(0, TOP_BAR_HEIGHT, screenWidth, TOP_BAR_HEIGHT + 2, 0xFF3A7CA5);

        final String keyCam = safeKeyName(RtsKeyMappings.TOGGLE_RTS_CAMERA, "F4");
        final String keyZone = safeKeyName(RtsKeyMappings.CYCLE_ZONE_TYPE, "Z");
        final String keyDelete = safeKeyName(RtsKeyMappings.TOGGLE_DELETE_MODE, "V");
        final String keyJob = safeKeyName(RtsKeyMappings.CYCLE_CITIZEN_JOB, "J");
        final String keyMode = safeKeyName(RtsKeyMappings.TOGGLE_PLACEMENT_MODE, "B");

        final String line1 = String.format(
            Locale.ROOT,
            "§6[RTS] §f식량:§e%d  목재:§a%d  석재:§7%d  광물:§b%d  |  §f시민:§d%d/%d§7(유휴:%d)  §f행복:§6%.1f",
            snap.food(),
            snap.wood(),
            snap.stone(),
            snap.minerals(),
            totalCitizens,
            maxCitizens,
            snap.idleCitizens(),
            happiness
        );

        final RtsZoneType activeZone = RtsCameraController.getActiveZoneType();
        final boolean deleteActive = RtsCameraController.isDeleteMode();
        final RtsZonePlacementMode activePlacementMode = RtsCameraController.getActivePlacementMode();

        final String modeLabel = deleteActive
            ? "§c[" + keyDelete + ":철거모드]"
            : "§a[" + keyZone + ":" + getZoneShortLabel(activeZone) + "]";

        final String line2 = String.format(
            Locale.ROOT,
            "%s §7| §b[%s:%s] §7| §f[%s:%s] §7| 고도:%.0fm · 선택:%d명 · [%s:복귀]",
            modeLabel,
            keyMode,
            activePlacementMode == RtsZonePlacementMode.MODE_B_BLUEPRINT_PREVIEW ? "방식B(미리보기)" : "방식A(자동)",
            keyJob,
            RtsCameraController.getActiveJobShortLabel(),
            RtsCameraController.getAltitude(),
            RtsCameraController.getSelectedCitizens().size(),
            keyCam
        );

        final int maxTextWidth = Math.max(120, screenWidth - 12);
        drawCenteredScaledString(graphics, mc.font, line1, screenWidth / 2, 4, maxTextWidth, 0xFFFFFF);
        drawCenteredScaledString(graphics, mc.font, line2, screenWidth / 2, 16, maxTextWidth, 0xE0E0E0);

        // Colony CTA banner or Hovered entity/zone inspector badge
        renderContextBanner(graphics, mc, screenWidth, colonyView, snap, keyDelete);

        // Interactive Cities-style bottom dock toolbar
        renderBottomToolbar(graphics, mc, screenWidth, screenHeight, activeZone, deleteActive, activePlacementMode);
    }

    private static void renderContextBanner(
        @NotNull final GuiGraphics graphics,
        @NotNull final Minecraft mc,
        final int screenWidth,
        @Nullable final IColonyView colonyView,
        @NotNull final RtsResourceAggregator.ColonySnapshot snap,
        @NotNull final String keyDelete)
    {
        String bannerText = null;
        int bgColor = 0xB8121A26;
        int borderColor = 0xFF3A7CA5;

        if (colonyView == null && snap.colonyId() <= 0)
        {
            bannerText = "§e⚠ 정착지 미설립: 시청(Town Hall) 블록 설치 후 우클릭하여 정착지를 설립하세요";
            bgColor = 0xCC2C1A0B;
            borderColor = 0xFFD4882A;
        }
        else
        {
            final BlockPos hoveredBuildingPos = RtsCameraController.getHoveredBuildingPos();
            final BlockPos hoveredBlockPos = RtsCameraController.getHoveredBlockPos();
            if (hoveredBuildingPos != null)
            {
                final IBuildingView buildingView = RtsMousePicker.pickBuildingView(mc, hoveredBuildingPos);
                if (buildingView != null)
                {
                    final String name = Component.translatable(buildingView.getBuildingDisplayName()).getString();
                    bannerText = String.format(
                        Locale.ROOT,
                        "§b[건물] §f%s §7(Lv.%d) | §a우클릭:관리  §eShift+우클릭:업그레이드",
                        name,
                        buildingView.getBuildingLevel()
                    );
                }
                else if (RtsMousePicker.isHutBlock(mc, hoveredBuildingPos))
                {
                    bannerText = "§e[미설립 건물/시청] §f우클릭으로 정착지 설립 및 관리 창 열기";
                }
            }
            else if (hoveredBlockPos != null)
            {
                final RtsZoneData zone = RtsInteractionBridge.findZoneContaining(hoveredBlockPos);
                if (zone != null)
                {
                    bannerText = String.format(
                        Locale.ROOT,
                        "§a[구역] §f%s §7| 우클릭:연결 건물  §c[%s]:철거",
                        zone.type().getDisplayName(),
                        keyDelete
                    );
                }
            }

            if (bannerText == null && !RtsCameraController.getSelectedCitizens().isEmpty())
            {
                bannerText = String.format(
                    Locale.ROOT,
                    "§a[시민 %d명 선택됨] §f땅/건물 우클릭: 이동·배치 | 빈 땅 좌클릭: 선택 해제",
                    RtsCameraController.getSelectedCitizens().size()
                );
                bgColor = 0xB80E2218;
                borderColor = 0xFF39C16C;
            }
        }

        if (bannerText == null)
        {
            return;
        }

        final int bannerTop = TOP_BAR_HEIGHT + 3;
        final int bannerHeight = 13;
        final int maxBannerW = Math.max(120, screenWidth - 16);
        final int rawW = mc.font.width(bannerText) + 14;
        final int boxW = Math.min(maxBannerW, Math.max(160, rawW));
        final int x1 = (screenWidth - boxW) / 2;
        final int x2 = x1 + boxW;

        graphics.fill(x1, bannerTop, x2, bannerTop + bannerHeight, bgColor);
        graphics.renderOutline(x1, bannerTop, boxW, bannerHeight, borderColor);
        drawCenteredScaledString(graphics, mc.font, bannerText, screenWidth / 2, bannerTop + 3, boxW - 8, 0xFFFFFF);
    }

    private static void renderBottomToolbar(
        @NotNull final GuiGraphics graphics,
        @NotNull final Minecraft mc,
        final int screenWidth,
        final int screenHeight,
        @NotNull final RtsZoneType activeZone,
        final boolean deleteActive,
        @NotNull final RtsZonePlacementMode activePlacementMode)
    {
        final List<ToolbarButton> buttons = computeToolbarButtons(
            screenWidth,
            screenHeight,
            activePlacementMode,
            RtsCameraController.getActiveJobShortLabel()
        );
        if (buttons.isEmpty())
        {
            return;
        }

        final int[] dock = computeToolbarDockBounds(buttons);
        graphics.fill(dock[0], dock[1], dock[2], dock[3], 0xC8111722);
        graphics.renderOutline(dock[0], dock[1], dock[2] - dock[0], dock[3] - dock[1], 0xFF3A7CA5);

        final double mouseGuiX = mc.mouseHandler.xpos() * (double) screenWidth / (double) Math.max(1, mc.getWindow().getScreenWidth());
        final double mouseGuiY = mc.mouseHandler.ypos() * (double) screenHeight / (double) Math.max(1, mc.getWindow().getScreenHeight());

        for (final ToolbarButton btn : buttons)
        {
            final boolean hovered = btn.contains(mouseGuiX, mouseGuiY);
            final boolean selected = switch (btn.actionType())
            {
                case ZONE_TYPE -> !deleteActive && btn.zoneType() == activeZone;
                case TOGGLE_DELETE -> deleteActive;
                case CYCLE_PLACEMENT_MODE -> activePlacementMode == RtsZonePlacementMode.MODE_B_BLUEPRINT_PREVIEW;
                case CYCLE_CITIZEN_JOB -> RtsCameraController.getActiveJobIdOverride() != null;
            };

            final int bgColor;
            final int borderColor;
            if (selected)
            {
                bgColor = btn.actionType() == ToolbarActionType.ZONE_TYPE && btn.zoneType() != null
                    ? btn.zoneType().argbColor(190)
                    : (btn.actionType() == ToolbarActionType.TOGGLE_DELETE ? 0xCC9E1F1F : 0xCC1E5D82);
                borderColor = hovered ? 0xFFFFFFFF : 0xFFEAF6FF;
            }
            else
            {
                bgColor = hovered ? 0xDE2B3C54 : 0xCC1A2230;
                borderColor = hovered ? 0xFF8FD0FF : 0xFF3A526B;
            }

            graphics.fill(btn.x(), btn.y(), btn.x() + btn.width(), btn.y() + btn.height(), bgColor);
            graphics.fill(btn.x(), btn.y(), btn.x() + btn.width(), btn.y() + 2, btn.accentArgb());
            graphics.renderOutline(btn.x(), btn.y(), btn.width(), btn.height(), borderColor);

            final int textColor = selected ? 0xFFFFFF : (hovered ? 0xFFFFFF : 0xD8E2EE);
            drawCenteredScaledString(
                graphics,
                mc.font,
                btn.label(),
                btn.x() + btn.width() / 2,
                btn.y() + 4,
                Math.max(12, btn.width() - 4),
                textColor
            );
        }
    }
}
