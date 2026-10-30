/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.launch.mixins;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.event.events.RenderEvent;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * @author Brady
 * @since 2/13/2020
 */
@Mixin(LevelRenderer.class)
public class MixinWorldRenderer {

    // 26.3: LevelRenderer.render no longer takes a DeltaTracker or the view matrix
    // directly. The matrix now lives on CameraRenderState, and the partial tick comes
    // from the client's own DeltaTracker.
    @Inject(
            method = "render",
            at = @At("RETURN")
    )
    private void onStartHand(final GraphicsResourceAllocator graphicsResourceAllocator, final boolean bl, final CameraRenderState cameraRenderState, final GpuBufferSlice gpuBufferSlice, final Vector4f vector4f, final boolean bl2, final boolean bl3, final CallbackInfo ci) {
        final Matrix4f viewMatrix = cameraRenderState.viewRotationMatrix;
        final float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        for (IBaritone ibaritone : BaritoneAPI.getProvider().getAllBaritones()) {
            PoseStack poseStack = new PoseStack();
            poseStack.mulPose(viewMatrix);
            ibaritone.getGameEventHandler().onRenderPass(new RenderEvent(partialTick, poseStack, new Matrix4f(viewMatrix)));
        }
    }
}
