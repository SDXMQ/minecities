package com.citiescolony.client;

import com.citiescolony.api.ColonyIds;
import com.citiescolony.api.ZoneType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Top-bar HUD overlay registered via Minecraft 26.2's separated Gui and Hud layer architecture.
 * Displays total Food, Wood, Stone, Minerals, Total/Idle citizen counts, and active Zone designation mode.
 */
public final class RtsTopBarHudOverlay {

    public static final Identifier LAYER_ID = ColonyIds.id("rts_top_bar_hud");

    private static volatile int foodCount = 0;
    private static volatile int woodCount = 0;
    private static volatile int stoneCount = 0;
    private static volatile int mineralCount = 0;
    private static volatile int totalCitizens = 0;
    private static volatile int idleCitizens = 0;

    private RtsTopBarHudOverlay() {
    }

    public static void updateHudStats(
            int food,
            int wood,
            int stone,
            int minerals,
            int totalCitizenCount,
            int idleCitizenCount
    ) {
        foodCount = Math.max(0, food);
        woodCount = Math.max(0, wood);
        stoneCount = Math.max(0, stone);
        mineralCount = Math.max(0, minerals);
        totalCitizens = Math.max(0, totalCitizenCount);
        idleCitizens = Math.max(0, idleCitizenCount);
    }

    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, LAYER_ID, RtsTopBarHudOverlay::renderTopBar);
    }

    public static void renderTopBar(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) {
            return;
        }
        // Only render when RTS camera mode is active or when the colony has active zones/citizens
        if (!RtsCameraController.isActive() && totalCitizens == 0 && ZoneGridBlazeRenderer.getClientZones().isEmpty()) {
            return;
        }

        int screenWidth = guiGraphics.guiWidth();
        int barWidth = Math.min(screenWidth - 16, 620);
        int barX = (screenWidth - barWidth) / 2;
        int barY = 6;
        int barHeight = 24;

        ZoneType currentZone = RtsCameraController.getSelectedZoneType();
        int borderColor = RtsCameraController.isDeleteZoneMode()
                ? 0xFFE53935
                : currentZone.argbColor(220);

        // Background panel & accent top border
        guiGraphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xCC10141C);
        guiGraphics.fill(barX, barY, barX + barWidth, barY + 2, borderColor);
        guiGraphics.fill(barX, barY + barHeight - 1, barX + barWidth, barY + barHeight, 0x88384252);

        Font font = mc.font;
        String statsText = String.format(
                "식량: %d | 목재: %d | 석재: %d | 광물: %d   주민: %d명 (유휴 %d명)",
                foodCount, woodCount, stoneCount, mineralCount, totalCitizens, idleCitizens
        );

        String modeLabel = RtsCameraController.isDeleteZoneMode()
                ? "[구역 삭제 모드 (X)]"
                : String.format("[구역: %s (Z)]", currentZone.displayName());

        int textY = barY + 8;
        guiGraphics.drawString(font, statsText, barX + 10, textY, 0xFFEAEFF5, true);

        int modeWidth = font.width(modeLabel);
        int modeColor = RtsCameraController.isDeleteZoneMode()
                ? 0xFFFF6B6B
                : currentZone.argbColor(255);
        guiGraphics.drawString(font, modeLabel, barX + barWidth - modeWidth - 10, textY, modeColor, true);
    }

    public static int getFoodCount() {
        return foodCount;
    }

    public static int getWoodCount() {
        return woodCount;
    }

    public static int getStoneCount() {
        return stoneCount;
    }

    public static int getMineralCount() {
        return mineralCount;
    }

    public static int getTotalCitizens() {
        return totalCitizens;
    }

    public static int getIdleCitizens() {
        return idleCitizens;
    }
}
