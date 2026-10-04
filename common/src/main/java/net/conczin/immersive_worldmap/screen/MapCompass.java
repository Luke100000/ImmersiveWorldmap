package net.conczin.immersive_worldmap.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

final class MapCompass {
    private static final int NORTH_COLOR = 0xFFF08078;
    private static final String[] LABELS = {"N", "E", "S", "W"};

    static void render(GuiGraphics graphics, Font font, float yawDegrees, int width, int height) {
        float centerX = width - 24f;
        float centerY = height - 28f;
        double yaw = Math.toRadians(yawDegrees);
        for (int i = 0; i < LABELS.length; i++) {
            double angle = yaw + (i + 1) * Math.PI / 2;
            float x = centerX + (float) Math.cos(angle) * 9f - font.width(LABELS[i]) / 2f;
            float y = centerY + (float) Math.sin(angle) * 9f - font.lineHeight / 2f;
            graphics.pose().pushPose();
            try {
                graphics.pose().translate(x, y, 0f);
                graphics.drawString(font, LABELS[i], 0, 0, i == 0 ? NORTH_COLOR : 0xFFB0B6BE, true);
            } finally {
                graphics.pose().popPose();
            }
        }
    }
}
