package net.conczin.immersive_worldmap.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.conczin.immersive_worldmap.mixin.PictureInPictureRendererAccessor;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;

/**
 * Draws the terrain into its own color and depth texture, which the GUI then blits.
 */
public class LodMapRenderer extends PictureInPictureRenderer<LodMapRenderState> {
    @Override
    public Class<LodMapRenderState> getRenderStateClass() {
        return LodMapRenderState.class;
    }

    @Override
    protected void renderToTexture(LodMapRenderState state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector) {
        PictureInPictureRendererAccessor accessor = (PictureInPictureRendererAccessor) this;
        LodChunkPageManager.INSTANCE.draw(state.plan(), state.modelView(), state.projection(),
                state.focusX(), state.focusZ(), state.range(), state.x1() - state.x0(), state.y1() - state.y0(),
                accessor.immersive_worldmap$getTextureView(), accessor.immersive_worldmap$getDepthTextureView());
    }

    @Override
    protected float getTranslateY(int height, int guiScale) {
        return height / 2f;
    }

    @Override
    protected String getTextureLabel() {
        return "immersive worldmap";
    }
}
