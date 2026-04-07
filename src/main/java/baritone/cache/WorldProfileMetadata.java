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

import baritone.api.utils.BetterBlockPos;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

final class WorldProfileMetadata {

    private static final int MAX_CHUNK_FINGERPRINTS = 64;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    String profileId;
    long createdAt;
    long lastSeenAt;
    String lastDimension;
    Integer lastPlayerX;
    Integer lastPlayerY;
    Integer lastPlayerZ;
    final LinkedHashMap<String, String> chunkFingerprints = new LinkedHashMap<>();

    static WorldProfileMetadata create(String profileId, String dimension, BetterBlockPos playerPos) {
        long now = System.currentTimeMillis();
        WorldProfileMetadata metadata = new WorldProfileMetadata();
        metadata.profileId = profileId;
        metadata.createdAt = now;
        metadata.lastSeenAt = now;
        metadata.lastDimension = dimension;
        metadata.setLastPlayerPos(playerPos);
        return metadata;
    }

    static WorldProfileMetadata load(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            WorldProfileMetadata metadata = GSON.fromJson(reader, WorldProfileMetadata.class);
            if (metadata == null) {
                return null;
            }
            metadata.normalize();
            return metadata;
        } catch (IOException ex) {
            System.out.println("Failed to load world profile metadata from " + file + ": " + ex.getMessage());
            return null;
        }
    }

    void save(Path file) {
        try {
            Path parent = file.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
        } catch (IOException ignored) {}

        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(this, writer);
        } catch (IOException ex) {
            System.out.println("Failed to save world profile metadata to " + file + ": " + ex.getMessage());
        }
    }

    void setLastPlayerPos(BetterBlockPos playerPos) {
        if (playerPos == null) {
            return;
        }
        this.lastPlayerX = playerPos.x;
        this.lastPlayerY = playerPos.y;
        this.lastPlayerZ = playerPos.z;
    }

    boolean hasLastPlayerPos() {
        return this.lastPlayerX != null && this.lastPlayerY != null && this.lastPlayerZ != null;
    }

    double distanceSq(BetterBlockPos playerPos) {
        if (playerPos == null || !this.hasLastPlayerPos()) {
            return Double.POSITIVE_INFINITY;
        }
        double dx = playerPos.x - this.lastPlayerX;
        double dy = playerPos.y - this.lastPlayerY;
        double dz = playerPos.z - this.lastPlayerZ;
        return dx * dx + dy * dy + dz * dz;
    }

    void recordChunkFingerprint(String key, String fingerprint) {
        this.chunkFingerprints.remove(key);
        this.chunkFingerprints.put(key, fingerprint);
        trimChunkFingerprints();
    }

    private void trimChunkFingerprints() {
        Iterator<Map.Entry<String, String>> iterator = this.chunkFingerprints.entrySet().iterator();
        while (this.chunkFingerprints.size() > MAX_CHUNK_FINGERPRINTS && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private void normalize() {
        if (this.profileId == null || this.profileId.isEmpty()) {
            this.profileId = "legacy";
        }
        trimChunkFingerprints();
    }
}
