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

package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

public class SurfaceCommand extends Command {

    private static final int SURFACE_SEARCH_RADIUS = 96;
    private static final int SURFACE_SEARCH_RADIUS_CHUNKS = SURFACE_SEARCH_RADIUS / 16;
    private static final int MAX_SURFACE_CANDIDATES = 64;
    private static final int[][] SURFACE_CHUNK_SAMPLES = {
            {8, 8},
            {4, 4},
            {12, 4},
            {4, 12},
            {12, 12}
    };

    protected SurfaceCommand(IBaritone baritone) {
        super(baritone, "surface", "top");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        final BetterBlockPos playerPos = ctx.playerFeet();
        if (isOpenToSky(playerPos)) {
            logDirect("Already at surface");
            return;
        }
        List<BetterBlockPos> candidates = findSurfaceCandidates(playerPos);
        if (!candidates.isEmpty()) {
            Goal goal = new GoalComposite(candidates.stream().map(GoalBlock::new).toArray(Goal[]::new));
            logDirect(String.format("Going to surface via %d nearby exit%s", candidates.size(), candidates.size() == 1 ? "" : "s"));
            baritone.getCustomGoalProcess().setGoalAndPath(goal);
            return;
        }
        Goal fallback = surfaceAboveCurrentColumn(playerPos);
        if (fallback != null) {
            logDirect(String.format("No nearby open-sky exit found, climbing current column via %s", fallback));
            baritone.getCustomGoalProcess().setGoalAndPath(fallback);
            return;
        }
        logDirect("No higher location found");
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Used to get out of caves, mines, ...";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The surface/top command looks for nearby loaded open-sky positions and paths to the closest one.",
                "",
                "If no nearby exit is found, it falls back to climbing the current column.",
                "",
                "Usage:",
                "> surface - Used to get out of caves, mines, ...",
                "> top - Used to get out of caves, mines, ..."
        );
    }

    private List<BetterBlockPos> findSurfaceCandidates(BetterBlockPos playerPos) {
        int originChunkX = playerPos.x >> 4;
        int originChunkZ = playerPos.z >> 4;
        LinkedHashSet<BetterBlockPos> candidates = new LinkedHashSet<>();
        for (int radius = 0; radius <= SURFACE_SEARCH_RADIUS_CHUNKS && candidates.size() < MAX_SURFACE_CANDIDATES; radius++) {
            addChunkRingCandidates(originChunkX, originChunkZ, radius, playerPos, candidates);
        }
        return candidates.stream()
                .sorted(Comparator.comparingDouble(playerPos::distanceSq))
                .collect(Collectors.toList());
    }

    private void addChunkRingCandidates(int originChunkX, int originChunkZ, int radius, BetterBlockPos playerPos, LinkedHashSet<BetterBlockPos> candidates) {
        if (radius == 0) {
            addChunkCandidates(originChunkX, originChunkZ, playerPos, candidates);
            return;
        }
        for (int chunkX = originChunkX - radius; chunkX <= originChunkX + radius && candidates.size() < MAX_SURFACE_CANDIDATES; chunkX++) {
            addChunkCandidates(chunkX, originChunkZ - radius, playerPos, candidates);
            addChunkCandidates(chunkX, originChunkZ + radius, playerPos, candidates);
        }
        for (int chunkZ = originChunkZ - radius + 1; chunkZ <= originChunkZ + radius - 1 && candidates.size() < MAX_SURFACE_CANDIDATES; chunkZ++) {
            addChunkCandidates(originChunkX - radius, chunkZ, playerPos, candidates);
            addChunkCandidates(originChunkX + radius, chunkZ, playerPos, candidates);
        }
    }

    private void addChunkCandidates(int chunkX, int chunkZ, BetterBlockPos playerPos, LinkedHashSet<BetterBlockPos> candidates) {
        if (!ctx.world().hasChunk(chunkX, chunkZ)) {
            return;
        }
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (int[] sample : SURFACE_CHUNK_SAMPLES) {
            BetterBlockPos candidate = surfaceCandidateAt(baseX + sample[0], baseZ + sample[1]);
            if (candidate == null || candidate.getY() <= playerPos.getY() || playerPos.distSqr(candidate) > SURFACE_SEARCH_RADIUS * SURFACE_SEARCH_RADIUS) {
                continue;
            }
            candidates.add(candidate);
            if (candidates.size() >= MAX_SURFACE_CANDIDATES) {
                return;
            }
        }
    }

    private BetterBlockPos surfaceCandidateAt(int x, int z) {
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        if (!ctx.world().hasChunk(chunkX, chunkZ)) {
            return null;
        }
        int y = ctx.world().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BetterBlockPos feet = new BetterBlockPos(x, y, z);
        if (!isSurfaceCandidate(feet)) {
            return null;
        }
        return feet;
    }

    private boolean isSurfaceCandidate(BetterBlockPos feet) {
        if (!isOpenToSky(feet)) {
            return false;
        }
        BetterBlockPos ground = feet.below();
        BlockState groundState = ctx.world().getBlockState(ground);
        return !groundState.isAir() && ctx.world().getFluidState(feet).isEmpty() && ctx.world().getFluidState(ground).isEmpty();
    }

    private boolean isOpenToSky(BetterBlockPos pos) {
        return ctx.world().canSeeSky(pos)
                && ctx.world().getBlockState(pos).getBlock() instanceof AirBlock
                && ctx.world().getBlockState(pos.above()).getBlock() instanceof AirBlock;
    }

    private Goal surfaceAboveCurrentColumn(BetterBlockPos playerPos) {
        final int surfaceLevel = ctx.world().getSeaLevel();
        final int worldHeight = ctx.world().getHeight();
        final int startingYPos = Math.max(playerPos.getY(), surfaceLevel);
        for (int currentIteratedY = startingYPos; currentIteratedY < worldHeight; currentIteratedY++) {
            BetterBlockPos newPos = new BetterBlockPos(playerPos.getX(), currentIteratedY, playerPos.getZ());
            if (!(ctx.world().getBlockState(newPos).getBlock() instanceof AirBlock) && newPos.getY() > playerPos.getY()) {
                return new GoalBlock(newPos.above());
            }
        }
        return null;
    }
}
