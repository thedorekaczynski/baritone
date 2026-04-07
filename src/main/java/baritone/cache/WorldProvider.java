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

import baritone.Baritone;
import baritone.api.cache.IWorldProvider;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.commons.lang3.SystemUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * @author Brady
 * @since 8/4/2018
 */
public class WorldProvider implements IWorldProvider {

    private static final String PROFILE_METADATA_FILE = "profile.json";
    private static final int RESOLUTION_FINGERPRINT_TARGET = 3;
    private static final Map<Path, WorldData> worldCache = new HashMap<>();

    private final Baritone baritone;
    private final IPlayerContext ctx;
    private WorldData currentWorld;
    private ActiveProfile currentProfile;
    private PendingWorldSession pendingWorld;

    /**
     * This lets us detect a broken load/unload hook.
     *
     * @see #detectAndHandleBrokenLoading()
     */
    private Level mcWorld;

    public WorldProvider(Baritone baritone) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
    }

    @Override
    public final WorldData getCurrentWorld() {
        this.detectAndHandleBrokenLoading();
        if (this.currentWorld == null && this.pendingWorld != null) {
            this.pendingWorld.tryFinalize(true);
        }
        return this.currentWorld;
    }

    /**
     * Called when a new world is initialized.
     *
     * @param world The new world
     */
    public final void initWorld(Level world) {
        this.pendingWorld = null;
        this.currentProfile = null;

        this.getSaveDirectories(world).ifPresent(dirs -> {
            writeReadme(dirs.readmeDir);

            if (dirs.singleplayer) {
                openWorld(dirs.baseDirectory, world, null);
                this.mcWorld = ctx.world();
                return;
            }

            this.pendingWorld = new PendingWorldSession(dirs, world);
            this.pendingWorld.tryFinalize(false);
            this.mcWorld = ctx.world();
        });
    }

    public final void closeWorld() {
        PendingWorldSession pending = this.pendingWorld;
        this.pendingWorld = null;

        if (this.currentProfile != null) {
            this.currentProfile.onClose();
        }
        this.currentProfile = null;

        WorldData world = this.currentWorld;
        this.currentWorld = null;
        this.mcWorld = null;
        if (world == null) {
            return;
        }
        world.onClose();
    }

    public final void observeChunk(LevelChunk chunk) {
        if (chunk == null || chunk.getLevel() != ctx.world()) {
            return;
        }
        if (this.pendingWorld != null) {
            this.pendingWorld.observeChunk(chunk);
        }
        if (this.currentProfile != null) {
            this.currentProfile.observeChunk(chunk);
        }
    }

    private void openWorld(Path root, Level world, ActiveProfile profile) {
        final Path worldDataDir = this.getWorldDataDirectory(root, world);
        try {
            Files.createDirectories(worldDataDir);
        } catch (IOException ignored) {}

        System.out.println("Baritone world data dir: " + worldDataDir);
        synchronized (worldCache) {
            this.currentWorld = worldCache.computeIfAbsent(worldDataDir, d -> new WorldData(d, world.dimensionType(), world.dimension()));
        }
        this.currentProfile = profile;
        this.mcWorld = ctx.world();
    }

    private void writeReadme(Path readmeDir) {
        try {
            Files.createDirectories(readmeDir);
            Files.write(
                    readmeDir.resolve("readme.txt"),
                    "https://github.com/cabaletta/baritone\n".getBytes(StandardCharsets.US_ASCII)
            );
        } catch (IOException ignored) {}
    }

    private Path getWorldDataDirectory(Path parent, Level world) {
        Identifier dimId = world.dimension().identifier();
        int height = world.dimensionType().logicalHeight();
        return parent.resolve(dimId.getNamespace()).resolve(dimId.getPath() + "_" + height);
    }

    private Optional<SaveDirectories> getSaveDirectories(Level world) {
        if (ctx.minecraft().hasSingleplayerServer()) {
            Path worldDir = ctx.minecraft().getSingleplayerServer().getWorldPath(LevelResource.ROOT);

            if (worldDir.relativize(ctx.minecraft().gameDirectory.toPath()).getNameCount() != 2) {
                worldDir = worldDir.getParent();
            }

            worldDir = worldDir.resolve("baritone");
            return Optional.of(new SaveDirectories(worldDir, worldDir, null, null, true));
        }

        final ServerData serverData = ctx.minecraft().getCurrentServer();
        if (serverData == null) {
            System.out.println("World seems to be a replay. Not loading Baritone cache.");
            currentWorld = null;
            mcWorld = ctx.world();
            return Optional.empty();
        }

        String rawServerName = serverData.isRealm() ? "realms" : serverData.ip;
        String legacyFolderName = rawServerName;
        if (SystemUtils.IS_OS_WINDOWS) {
            legacyFolderName = legacyFolderName.replace(":", "_");
        }
        String serverKey = normalizeServerKey(rawServerName);
        Path serverRoot = baritone.getDirectory().resolve("servers").resolve(serverKey);
        Path profilesDir = serverRoot.resolve("profiles");
        Path legacyRoot = baritone.getDirectory().resolve(legacyFolderName);
        return Optional.of(new SaveDirectories(serverRoot, baritone.getDirectory(), profilesDir, legacyRoot, false));
    }

    private static String normalizeServerKey(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-') {
                out.append(c);
            } else {
                out.append('_');
            }
        }
        return out.length() == 0 ? "unknown" : out.toString();
    }

    private List<ProfileCandidate> loadCandidates(SaveDirectories dirs) {
        List<ProfileCandidate> out = new ArrayList<>();

        if (dirs.profilesDirectory != null && Files.isDirectory(dirs.profilesDirectory)) {
            try (Stream<Path> stream = Files.list(dirs.profilesDirectory)) {
                stream.filter(Files::isDirectory)
                        .sorted()
                        .forEach(path -> out.add(new ProfileCandidate(path, false)));
            } catch (IOException ignored) {}
        }

        if (dirs.legacyRoot != null && Files.isDirectory(dirs.legacyRoot)) {
            out.add(new ProfileCandidate(dirs.legacyRoot, true));
        }

        out.sort(Comparator.comparingLong((ProfileCandidate c) -> c.lastSeenAt()).reversed());
        return out;
    }

    /**
     * Why does this exist instead of fixing the event? Some mods break the event. Lol.
     */
    private void detectAndHandleBrokenLoading() {
        if (this.mcWorld != ctx.world()) {
            if (this.currentWorld != null || this.pendingWorld != null) {
                System.out.println("mc.world unloaded unnoticed! Unloading Baritone cache now.");
                closeWorld();
            }
            if (ctx.world() != null) {
                System.out.println("mc.world loaded unnoticed! Loading Baritone cache now.");
                initWorld(ctx.world());
            }
        } else if (this.currentWorld == null && this.pendingWorld == null && ctx.world() != null
                && (ctx.minecraft().hasSingleplayerServer() || ctx.minecraft().getCurrentServer() != null)) {
            System.out.println("Retrying to load Baritone cache");
            initWorld(ctx.world());
        }
    }

    private final class ActiveProfile {

        private final Path root;
        private final Path metadataPath;
        private final WorldProfileMetadata metadata;
        private final Map<String, String> sessionFingerprints = new LinkedHashMap<>();

        private ActiveProfile(Path root, WorldProfileMetadata metadata) {
            this.root = root;
            this.metadataPath = root.resolve(PROFILE_METADATA_FILE);
            this.metadata = metadata;
        }

        private void observeChunk(LevelChunk chunk) {
            String key = WorldFingerprint.chunkKey(chunk);
            if (this.sessionFingerprints.containsKey(key)) {
                return;
            }
            String fingerprint = WorldFingerprint.fingerprint(chunk);
            this.sessionFingerprints.put(key, fingerprint);
            this.metadata.recordChunkFingerprint(key, fingerprint);
        }

        private void onClose() {
            this.metadata.lastSeenAt = System.currentTimeMillis();
            this.metadata.lastDimension = mcWorld == null ? this.metadata.lastDimension : WorldFingerprint.dimensionKey(mcWorld);
            this.metadata.setLastPlayerPos(currentPlayerPos());
            this.metadata.save(this.metadataPath);
        }
    }

    private final class PendingWorldSession {

        private final SaveDirectories directories;
        private final Level world;
        private final List<ProfileCandidate> candidates;
        private final ProfileCandidate provisionalCandidate;
        private final Map<String, String> observedFingerprints = new LinkedHashMap<>();
        private final List<int[]> observedChunkPositions = new ArrayList<>();

        private PendingWorldSession(SaveDirectories directories, Level world) {
            this.directories = directories;
            this.world = world;
            this.candidates = loadCandidates(directories);
            this.provisionalCandidate = this.candidates.isEmpty() ? null : this.candidates.get(0);
        }

        private void observeChunk(LevelChunk chunk) {
            String key = WorldFingerprint.chunkKey(chunk);
            if (!this.observedFingerprints.containsKey(key)) {
                this.observedFingerprints.put(key, WorldFingerprint.fingerprint(chunk));
                this.observedChunkPositions.add(new int[]{chunk.getPos().x(), chunk.getPos().z()});
            }
            tryFinalize(false);
        }

        private void tryFinalize(boolean force) {
            if (pendingWorld != this || currentWorld != null) {
                return;
            }
            ProfileCandidate chosen = selectCandidate(force);
            if (chosen == null) {
                return;
            }

            WorldProfileMetadata metadata = chosen.metadata != null
                    ? chosen.metadata
                    : WorldProfileMetadata.create(chosen.profileId(), WorldFingerprint.dimensionKey(this.world), currentPlayerPos());
            metadata.lastDimension = WorldFingerprint.dimensionKey(this.world);
            metadata.setLastPlayerPos(currentPlayerPos());
            metadata.lastSeenAt = System.currentTimeMillis();
            for (Map.Entry<String, String> entry : this.observedFingerprints.entrySet()) {
                metadata.recordChunkFingerprint(entry.getKey(), entry.getValue());
            }

            pendingWorld = null;
            ActiveProfile profile = new ActiveProfile(chosen.root, metadata);
            profile.metadata.save(profile.metadataPath);
            openWorld(chosen.root, this.world, profile);
            replayObservedChunks();
        }

        private void replayObservedChunks() {
            if (currentWorld == null) {
                return;
            }
            for (int[] chunkPos : this.observedChunkPositions) {
                LevelChunk loaded = this.world.getChunk(chunkPos[0], chunkPos[1]);
                currentWorld.getCachedWorld().queueForPacking(loaded);
            }
        }

        private ProfileCandidate selectCandidate(boolean force) {
            if (this.candidates.isEmpty()) {
                return createNewCandidate();
            }

            if (this.candidates.size() == 1 && this.observedFingerprints.isEmpty()) {
                return this.candidates.get(0);
            }

            List<CandidateScore> scores = new ArrayList<>();
            for (ProfileCandidate candidate : this.candidates) {
                scores.add(score(candidate));
            }
            scores.sort(Comparator
                    .comparingInt((CandidateScore s) -> s.score).reversed()
                    .thenComparingInt(s -> s.chunkMatches).reversed()
                    .thenComparingLong(s -> s.candidate.lastSeenAt()).reversed());

            CandidateScore best = scores.get(0);
            CandidateScore provisional = findScore(scores, this.provisionalCandidate);

            if (best.chunkMatches > 0 && best.chunkMismatches == 0) {
                return best.candidate;
            }

            if (best.chunkMismatches >= 2 && best.chunkMatches == 0
                    && (force || this.observedFingerprints.size() >= RESOLUTION_FINGERPRINT_TARGET)) {
                return createNewCandidate();
            }

            if (provisional != null && provisional.chunkMismatches == 0
                    && (force || this.observedFingerprints.size() >= RESOLUTION_FINGERPRINT_TARGET)) {
                return provisional.candidate;
            }

            if (force) {
                if (best.chunkMismatches == 0 || best.chunkMatches > 0) {
                    return best.candidate;
                }
                return createNewCandidate();
            }

            return null;
        }

        private CandidateScore findScore(List<CandidateScore> scores, ProfileCandidate candidate) {
            if (candidate == null) {
                return null;
            }
            for (CandidateScore score : scores) {
                if (score.candidate.root.equals(candidate.root)) {
                    return score;
                }
            }
            return null;
        }

        private CandidateScore score(ProfileCandidate candidate) {
            int score = 0;
            int chunkMatches = 0;
            int chunkMismatches = 0;
            WorldProfileMetadata metadata = candidate.metadata;

            if (metadata != null) {
                if (WorldFingerprint.dimensionKey(this.world).equals(metadata.lastDimension)) {
                    score += 20;
                }

                BetterBlockPos playerPos = currentPlayerPos();
                double distanceSq = metadata.distanceSq(playerPos);
                if (distanceSq <= 64 * 64) {
                    score += 40;
                } else if (distanceSq <= 256 * 256) {
                    score += 20;
                } else if (distanceSq <= 1024 * 1024) {
                    score += 8;
                }

                for (Map.Entry<String, String> entry : this.observedFingerprints.entrySet()) {
                    String cached = metadata.chunkFingerprints.get(entry.getKey());
                    if (cached == null) {
                        continue;
                    }
                    if (cached.equals(entry.getValue())) {
                        score += 250;
                        chunkMatches++;
                    } else {
                        score -= 350;
                        chunkMismatches++;
                    }
                }
            }

            return new CandidateScore(candidate, score, chunkMatches, chunkMismatches);
        }

        private ProfileCandidate createNewCandidate() {
            String profileId = "profile-" + UUID.randomUUID().toString().substring(0, 8);
            Path root = this.directories.profilesDirectory.resolve(profileId);
            return new ProfileCandidate(root, false, WorldProfileMetadata.create(profileId, WorldFingerprint.dimensionKey(this.world), currentPlayerPos()));
        }
    }

    private BetterBlockPos currentPlayerPos() {
        return ctx.player() == null ? null : ctx.playerFeet();
    }

    private static final class SaveDirectories {

        private final Path baseDirectory;
        private final Path readmeDir;
        private final Path profilesDirectory;
        private final Path legacyRoot;
        private final boolean singleplayer;

        private SaveDirectories(Path baseDirectory, Path readmeDir, Path profilesDirectory, Path legacyRoot, boolean singleplayer) {
            this.baseDirectory = baseDirectory;
            this.readmeDir = readmeDir;
            this.profilesDirectory = profilesDirectory;
            this.legacyRoot = legacyRoot;
            this.singleplayer = singleplayer;
        }
    }

    private static final class ProfileCandidate {

        private final Path root;
        private final WorldProfileMetadata metadata;
        private final boolean legacy;

        private ProfileCandidate(Path root, boolean legacy) {
            this(root, legacy, WorldProfileMetadata.load(root.resolve(PROFILE_METADATA_FILE)));
        }

        private ProfileCandidate(Path root, boolean legacy, WorldProfileMetadata metadata) {
            this.root = root;
            this.legacy = legacy;
            this.metadata = metadata;
        }

        private long lastSeenAt() {
            return this.metadata == null ? 0L : this.metadata.lastSeenAt;
        }

        private String profileId() {
            if (this.metadata != null && this.metadata.profileId != null) {
                return this.metadata.profileId;
            }
            return this.legacy ? "legacy" : this.root.getFileName().toString();
        }
    }

    private static final class CandidateScore {

        private final ProfileCandidate candidate;
        private final int score;
        private final int chunkMatches;
        private final int chunkMismatches;

        private CandidateScore(ProfileCandidate candidate, int score, int chunkMatches, int chunkMismatches) {
            this.candidate = candidate;
            this.score = score;
            this.chunkMatches = chunkMatches;
            this.chunkMismatches = chunkMismatches;
        }
    }
}
