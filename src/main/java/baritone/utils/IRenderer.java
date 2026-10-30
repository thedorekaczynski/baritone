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

package baritone.utils;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.utils.accessor.IEntityRenderManager;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Line rendering for Baritone.
 *
 * <p>Minecraft 26.2 deleted {@code Tesselator}, {@code VertexFormat.Mode} and
 * {@code RenderType.draw(MeshData)}, so the old immediate-mode vertex path is gone.
 * Everything now goes through the Gizmos API instead.
 *
 * <p>The public API is unchanged apart from the buffer type: callers still
 * {@link #startLines}, emit lines into the returned buffer, then {@link #endLines}.
 * {@link LineBuffer} replaces {@code BufferBuilder}.
 *
 * <p><b>Coordinate convention:</b> every {@code emit*} method takes <i>camera-relative</i>
 * coordinates, exactly as before — callers such as {@code PathRenderer} subtract the
 * render position themselves. Gizmos, however, want absolute world coordinates, so the
 * render position is added back once, centrally, in {@link #endLines}. Do not offset
 * coordinates at the call sites.
 */
public interface IRenderer {

    IEntityRenderManager renderManager = (IEntityRenderManager) Minecraft.getInstance().getEntityRenderDispatcher();
    Settings settings = BaritoneAPI.getSettings();

    float[] color = new float[]{1.0F, 1.0F, 1.0F, 255.0F};

    /**
     * Collects camera-relative line segments so {@link #endLines} can emit them all with
     * the correct depth behaviour. Gizmos are emitted immediately when created, and
     * "always on top" is a per-gizmo flag that is only known at flush time, so the
     * segments have to be buffered rather than drawn as they arrive.
     */
    final class LineBuffer {
        private final int argb;
        private final List<double[]> segments = new ArrayList<>();

        private LineBuffer(int argb) {
            this.argb = argb;
        }

        private void add(double x1, double y1, double z1, double x2, double y2, double z2, float lineWidth) {
            this.segments.add(new double[]{x1, y1, z1, x2, y2, z2, lineWidth});
        }
    }

    static void glColor(Color color, float alpha) {
        float[] colorComponents = color.getColorComponents(null);
        IRenderer.color[0] = colorComponents[0];
        IRenderer.color[1] = colorComponents[1];
        IRenderer.color[2] = colorComponents[2];
        IRenderer.color[3] = alpha;
    }

    private static int packArgb() {
        int a = (int) (Math.min(Math.max(color[3], 0.0F), 1.0F) * 255.0F) & 0xFF;
        int r = (int) (Math.min(Math.max(color[0], 0.0F), 1.0F) * 255.0F) & 0xFF;
        int g = (int) (Math.min(Math.max(color[1], 0.0F), 1.0F) * 255.0F) & 0xFF;
        int b = (int) (Math.min(Math.max(color[2], 0.0F), 1.0F) * 255.0F) & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    static LineBuffer startLines(Color color, float alpha) {
        glColor(color, alpha);
        return new LineBuffer(packArgb());
    }

    static LineBuffer startLines(Color color) {
        return startLines(color, .4f);
    }

    static void endLines(LineBuffer buffer, boolean ignoredDepth) {
        if (buffer == null || buffer.segments.isEmpty()) {
            return;
        }

        // Buffered coordinates are camera-relative; Gizmos want world space.
        final double vpX = renderManager.renderPosX();
        final double vpY = renderManager.renderPosY();
        final double vpZ = renderManager.renderPosZ();

        for (double[] s : buffer.segments) {
            Vec3 from = new Vec3(s[0] + vpX, s[1] + vpY, s[2] + vpZ);
            Vec3 to = new Vec3(s[3] + vpX, s[4] + vpY, s[5] + vpZ);
            var gizmo = Gizmos.line(from, to, buffer.argb, (float) s[6]);
            if (ignoredDepth) {
                gizmo.setAlwaysOnTop();
            }
        }
        buffer.segments.clear();
    }

    static void emitLine(LineBuffer buffer, PoseStack stack, double x1, double y1, double z1, double x2, double y2, double z2, float lineWidth) {
        // Normals were only needed by the old line render type; Gizmos derives its own.
        buffer.add(x1, y1, z1, x2, y2, z2, lineWidth);
    }

    static void emitLine(LineBuffer buffer, PoseStack stack,
                         double x1, double y1, double z1,
                         double x2, double y2, double z2,
                         double nx, double ny, double nz,
                         float lineWidth
    ) {
        buffer.add(x1, y1, z1, x2, y2, z2, lineWidth);
    }

    static void emitLine(LineBuffer buffer, PoseStack stack,
                         float x1, float y1, float z1,
                         float x2, float y2, float z2,
                         float nx, float ny, float nz,
                         float lineWidth
    ) {
        buffer.add(x1, y1, z1, x2, y2, z2, lineWidth);
    }

    static void emitAABB(LineBuffer buffer, PoseStack stack, AABB aabb, float lineWidth) {
        AABB toDraw = aabb.move(-renderManager.renderPosX(), -renderManager.renderPosY(), -renderManager.renderPosZ());

        // bottom
        emitLine(buffer, stack, toDraw.minX, toDraw.minY, toDraw.minZ, toDraw.maxX, toDraw.minY, toDraw.minZ, 1.0, 0.0, 0.0, lineWidth);
        emitLine(buffer, stack, toDraw.maxX, toDraw.minY, toDraw.minZ, toDraw.maxX, toDraw.minY, toDraw.maxZ, 0.0, 0.0, 1.0, lineWidth);
        emitLine(buffer, stack, toDraw.maxX, toDraw.minY, toDraw.maxZ, toDraw.minX, toDraw.minY, toDraw.maxZ, -1.0, 0.0, 0.0, lineWidth);
        emitLine(buffer, stack, toDraw.minX, toDraw.minY, toDraw.maxZ, toDraw.minX, toDraw.minY, toDraw.minZ, 0.0, 0.0, -1.0, lineWidth);
        // top
        emitLine(buffer, stack, toDraw.minX, toDraw.maxY, toDraw.minZ, toDraw.maxX, toDraw.maxY, toDraw.minZ, 1.0, 0.0, 0.0, lineWidth);
        emitLine(buffer, stack, toDraw.maxX, toDraw.maxY, toDraw.minZ, toDraw.maxX, toDraw.maxY, toDraw.maxZ, 0.0, 0.0, 1.0, lineWidth);
        emitLine(buffer, stack, toDraw.maxX, toDraw.maxY, toDraw.maxZ, toDraw.minX, toDraw.maxY, toDraw.maxZ, -1.0, 0.0, 0.0, lineWidth);
        emitLine(buffer, stack, toDraw.minX, toDraw.maxY, toDraw.maxZ, toDraw.minX, toDraw.maxY, toDraw.minZ, 0.0, 0.0, -1.0, lineWidth);
        // corners
        emitLine(buffer, stack, toDraw.minX, toDraw.minY, toDraw.minZ, toDraw.minX, toDraw.maxY, toDraw.minZ, 0.0, 1.0, 0.0, lineWidth);
        emitLine(buffer, stack, toDraw.maxX, toDraw.minY, toDraw.minZ, toDraw.maxX, toDraw.maxY, toDraw.minZ, 0.0, 1.0, 0.0, lineWidth);
        emitLine(buffer, stack, toDraw.maxX, toDraw.minY, toDraw.maxZ, toDraw.maxX, toDraw.maxY, toDraw.maxZ, 0.0, 1.0, 0.0, lineWidth);
        emitLine(buffer, stack, toDraw.minX, toDraw.minY, toDraw.maxZ, toDraw.minX, toDraw.maxY, toDraw.maxZ, 0.0, 1.0, 0.0, lineWidth);
    }

    static void emitAABB(LineBuffer buffer, PoseStack stack, AABB aabb, double expand, float lineWidth) {
        emitAABB(buffer, stack, aabb.inflate(expand, expand, expand), lineWidth);
    }

    static void emitLine(LineBuffer buffer, PoseStack stack, Vec3 start, Vec3 end, float lineWidth) {
        double vpX = renderManager.renderPosX();
        double vpY = renderManager.renderPosY();
        double vpZ = renderManager.renderPosZ();
        emitLine(buffer, stack, start.x - vpX, start.y - vpY, start.z - vpZ, end.x - vpX, end.y - vpY, end.z - vpZ, lineWidth);
    }

}
