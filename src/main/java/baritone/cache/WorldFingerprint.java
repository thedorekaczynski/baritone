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

package baritone.cache;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class WorldFingerprint {

    private WorldFingerprint() {}

    static String chunkKey(LevelChunk chunk) {
        return chunkKey(chunk.getLevel(), chunk.getPos().x(), chunk.getPos().z());
    }

    static String chunkKey(Level world, int chunkX, int chunkZ) {
        return world.dimension().identifier() + "|" + chunkX + "|" + chunkZ;
    }

    static String dimensionKey(Level world) {
        return world.dimension().identifier().toString();
    }

    static String fingerprint(LevelChunk chunk) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Missing SHA-256 implementation", ex);
        }
        updateInt(digest, chunk.getPos().x());
        updateInt(digest, chunk.getPos().z());
        updateString(digest, dimensionKey(chunk.getLevel()));

        int minY = chunk.getMinY();
        int maxY = minY + chunk.getLevel().dimensionType().height();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();

        for (int y = minY; y < maxY; y += 8) {
            for (int z = 0; z < 16; z += 2) {
                for (int x = 0; x < 16; x += 2) {
                    mutable.set(baseX + x, y, baseZ + z);
                    updateString(digest, chunk.getBlockState(mutable).toString());
                }
            }
        }

        return toHex(digest.digest());
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void updateString(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >>> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }
}
