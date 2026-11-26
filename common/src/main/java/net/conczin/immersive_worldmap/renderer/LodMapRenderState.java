package net.conczin.immersive_worldmap.renderer;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * Everything needed to draw the terrain once the GUI is rendered, all coordinates in GUI units.
 */
public record LodMapRenderState(
        LodChunkPageManager.Plan plan,
        Matrix4f modelView,
        Matrix4f projection,
        float focusX,
        float focusZ,
        LodChunkVisibilitySelector.RenderRange range,
        int x0,
        int y0,
        int x1,
        int y1,
        float scale,
        @Nullable ScreenRectangle scissorArea,
        @Nullable ScreenRectangle bounds
) implements PictureInPictureRenderState {
    public LodMapRenderState(LodChunkPageManager.Plan plan, Matrix4f modelView, Matrix4f projection,
                             float focusX, float focusZ, LodChunkVisibilitySelector.RenderRange range,
                             int width, int height) {
        this(plan, modelView, projection, focusX, focusZ, range, 0, 0, width, height, 1f, null,
                PictureInPictureRenderState.getBounds(0, 0, width, height, null));
    }
}
