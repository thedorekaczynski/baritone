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

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.ICowHuntProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.selection.ISelection;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class CowHuntProcess extends BaritoneProcessHelper implements ICowHuntProcess {

    private static final int SEARCH_CHUNK_STRIDE = 2;
    private static final int SEARCH_SAMPLE_LIMIT = 256;
    private static final int EAT_HUNGER_THRESHOLD = 14;
    private static final double ATTACK_RANGE_SQ = 16.0D;
    private static final double LOOT_RANGE_SQ = 144.0D;
    private static final int RECENT_SEARCH_LIMIT = 32;

    private boolean active;
    private BetterBlockPos origin;
    private BetterBlockPos lastFeet;
    private BetterBlockPos currentSearchTarget;
    private int searchSpiralIndex;
    private UUID trackedTarget;
    private BetterBlockPos trackedTargetLastPos;
    private long startedAt;
    private int ticks;
    private int cowsKilled;
    private int lootTargetsVisited;
    private int searchTargetsVisited;
    private double distanceTravelled;
    private final Deque<Long> recentSearchChunks;
    private final Map<String, List<AABB>> protectedAreas;
    private String lastSummary;

    public CowHuntProcess(Baritone baritone) {
        super(baritone);
        this.recentSearchChunks = new ArrayDeque<>();
        this.protectedAreas = new HashMap<>();
        this.lastSummary = "No cow hunt has run yet";
    }

    @Override
    public void hunt() {
        active = true;
        origin = ctx.playerFeet();
        lastFeet = origin;
        currentSearchTarget = null;
        searchSpiralIndex = 0;
        trackedTarget = null;
        trackedTargetLastPos = null;
        startedAt = System.currentTimeMillis();
        ticks = 0;
        cowsKilled = 0;
        lootTargetsVisited = 0;
        searchTargetsVisited = 0;
        distanceTravelled = 0;
        recentSearchChunks.clear();
        logDirect(String.format("Cow hunt started. %d protected zone(s) active.", protectedZoneCount()));
    }

    @Override
    public String status() {
        if (active) {
            return formatSummary("running");
        }
        return lastSummary;
    }

    @Override
    public int protect(String name, ISelection[] selections) {
        ArrayList<AABB> boxes = new ArrayList<>();
        for (ISelection selection : selections) {
            boxes.add(selection.aabb());
        }
        protectedAreas.put(name.toLowerCase(Locale.US), boxes);
        return boxes.size();
    }

    @Override
    public int clearProtected(String name) {
        List<AABB> removed = protectedAreas.remove(name.toLowerCase(Locale.US));
        return removed == null ? 0 : removed.size();
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        ticks++;
        updateDistanceTravelled();
        trackTargetDeath();
        baritone.getInputOverrideHandler().clearAllKeys();

        if (ctx.player() == null || ctx.world() == null) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (ticks % 200 == 0) {
            logDirect(status());
        }

        if (handleEating()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        ItemEntity loot = nearestLoot();
        if (loot != null) {
            currentSearchTarget = null;
            if (ctx.player().distanceToSqr(loot) > 4.0D) {
                return new PathingCommand(goalForEntity(loot), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
            }
            lootTargetsVisited++;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        Cow cow = nearestCow();
        if (cow != null) {
            currentSearchTarget = null;
            trackedTarget = cow.getUUID();
            trackedTargetLastPos = new BetterBlockPos(cow.blockPosition());
            if (ctx.player().distanceToSqr(cow) <= ATTACK_RANGE_SQ) {
                attack(cow);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            return new PathingCommand(goalForEntity(cow), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }

        if (calcFailed || currentSearchTarget == null || ctx.playerFeet().distSqr(currentSearchTarget) <= 16.0D) {
            if (currentSearchTarget != null && ctx.playerFeet().distSqr(currentSearchTarget) <= 16.0D) {
                searchTargetsVisited++;
            }
            currentSearchTarget = pickNextSearchTarget();
            if (currentSearchTarget != null) {
                logDirect(String.format("Searching near %d %d %d", currentSearchTarget.x, currentSearchTarget.y, currentSearchTarget.z));
            }
        }

        if (currentSearchTarget == null) {
            return new PathingCommand(new GoalBlock(origin), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
        return new PathingCommand(new GoalBlock(currentSearchTarget), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    @Override
    public void onLostControl() {
        if (!active) {
            return;
        }
        active = false;
        currentSearchTarget = null;
        trackedTarget = null;
        trackedTargetLastPos = null;
        lastSummary = formatSummary("stopped");
        logDirect(lastSummary);
    }

    @Override
    public String displayName0() {
        return "Cow Hunt";
    }

    private void updateDistanceTravelled() {
        BetterBlockPos currentFeet = ctx.playerFeet();
        if (lastFeet != null) {
            distanceTravelled += Math.sqrt(lastFeet.distSqr(currentFeet));
        }
        lastFeet = currentFeet;
    }

    private void trackTargetDeath() {
        if (trackedTarget == null || trackedTargetLastPos == null) {
            return;
        }
        Entity tracked = ctx.entitiesStream().filter(entity -> trackedTarget.equals(entity.getUUID())).findFirst().orElse(null);
        if (tracked != null) {
            trackedTargetLastPos = new BetterBlockPos(tracked.blockPosition());
            return;
        }
        boolean nearbyDrops = ctx.entitiesStream()
                .filter(ItemEntity.class::isInstance)
                .map(ItemEntity.class::cast)
                .filter(this::isWantedLoot)
                .anyMatch(item -> item.blockPosition().closerThan(trackedTargetLastPos, 6.0D));
        if (nearbyDrops) {
            cowsKilled++;
        }
        trackedTarget = null;
        trackedTargetLastPos = null;
    }

    private boolean handleEating() {
        FoodData food = ctx.player().getFoodData();
        if (ctx.player().isUsingItem()) {
            return true;
        }
        if (food.getFoodLevel() > EAT_HUNGER_THRESHOLD) {
            return false;
        }
        if (!baritone.getInventoryBehavior().throwaway(true, stack -> stack.getItem().components().has(DataComponents.FOOD))) {
            return false;
        }
        ctx.playerController().syncHeldItem();
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
        return true;
    }

    private void attack(Cow cow) {
        selectWeapon();
        Rotation rotation = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), cow.getEyePosition(), ctx.playerRotations());
        baritone.getLookBehavior().updateTarget(rotation, false);
        if (ctx.player().getAttackStrengthScale(0.0F) < 0.92F) {
            return;
        }
        ctx.playerController().syncHeldItem();
        ctx.minecraft().gameMode.attack(ctx.player(), cow);
        ctx.player().swing(InteractionHand.MAIN_HAND);
    }

    private void selectWeapon() {
        if (baritone.getInventoryBehavior().throwaway(true, stack ->
                stack.getItem() instanceof AxeItem
                        || stack.is(Items.NETHERITE_SWORD)
                        || stack.is(Items.DIAMOND_SWORD)
                        || stack.is(Items.IRON_SWORD)
                        || stack.is(Items.GOLDEN_SWORD)
                        || stack.is(Items.STONE_SWORD)
                        || stack.is(Items.WOODEN_SWORD))) {
            ctx.playerController().syncHeldItem();
        }
    }

    private Cow nearestCow() {
        return ctx.entitiesStream()
                .filter(Cow.class::isInstance)
                .map(Cow.class::cast)
                .filter(Entity::isAlive)
                .filter(cow -> !cow.isBaby())
                .filter(cow -> !isProtected(cow.blockPosition()))
                .min(Comparator.comparingDouble(cow -> cow.distanceToSqr(ctx.player())))
                .orElse(null);
    }

    private ItemEntity nearestLoot() {
        return ctx.entitiesStream()
                .filter(ItemEntity.class::isInstance)
                .map(ItemEntity.class::cast)
                .filter(this::isWantedLoot)
                .filter(item -> !isProtected(item.blockPosition()))
                .filter(item -> item.distanceToSqr(ctx.player()) <= LOOT_RANGE_SQ)
                .min(Comparator.comparingDouble(item -> item.distanceToSqr(ctx.player())))
                .orElse(null);
    }

    private boolean isWantedLoot(ItemEntity item) {
        return item.isAlive() && (item.getItem().is(Items.BEEF) || item.getItem().is(Items.LEATHER) || item.getItem().is(Items.COOKED_BEEF));
    }

    private Goal goalForEntity(Entity entity) {
        return new GoalBlock(new BetterBlockPos(entity.blockPosition()));
    }

    private BetterBlockPos pickNextSearchTarget() {
        int originChunkX = origin.x >> 4;
        int originChunkZ = origin.z >> 4;
        for (int checked = 0; checked < SEARCH_SAMPLE_LIMIT; checked++) {
            int[] offset = spiralOffset(searchSpiralIndex++);
            int chunkX = originChunkX + offset[0] * SEARCH_CHUNK_STRIDE;
            int chunkZ = originChunkZ + offset[1] * SEARCH_CHUNK_STRIDE;
            BetterBlockPos candidate = surfaceCandidateInChunk(chunkX, chunkZ);
            if (candidate != null) {
                rememberSearch(candidate);
                return candidate;
            }
        }
        return null;
    }

    private int[] spiralOffset(int index) {
        if (index == 0) {
            return new int[]{0, 0};
        }
        int layer = 1;
        while ((2 * layer + 1) * (2 * layer + 1) <= index) {
            layer++;
        }
        int sideLen = layer * 2;
        int maxValue = (2 * layer + 1) * (2 * layer + 1) - 1;
        int offsetOnRing = maxValue - index;
        int side = offsetOnRing / sideLen;
        int pos = offsetOnRing % sideLen;
        switch (side) {
            case 0:
                return new int[]{layer - pos, -layer};
            case 1:
                return new int[]{-layer, -layer + pos};
            case 2:
                return new int[]{-layer + pos, layer};
            default:
                return new int[]{layer, layer - pos};
        }
    }

    private BetterBlockPos surfaceCandidateInChunk(int chunkX, int chunkZ) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        BetterBlockPos[] samples = new BetterBlockPos[]{
                surfaceCandidateAt(baseX + 8, baseZ + 8),
                surfaceCandidateAt(baseX + 4, baseZ + 4),
                surfaceCandidateAt(baseX + 12, baseZ + 4),
                surfaceCandidateAt(baseX + 4, baseZ + 12),
                surfaceCandidateAt(baseX + 12, baseZ + 12)
        };
        BetterBlockPos playerFeet = ctx.playerFeet();
        BetterBlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (BetterBlockPos sample : samples) {
            if (sample == null) {
                continue;
            }
            double score = sample.distSqr(playerFeet);
            if (best == null || score < bestScore) {
                best = sample;
                bestScore = score;
            }
        }
        return best;
    }

    private BetterBlockPos surfaceCandidateAt(int x, int z) {
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        if (!ctx.world().hasChunk(chunkX, chunkZ)) {
            return null;
        }
        long chunkKey = ChunkPos.pack(chunkX, chunkZ);
        if (recentSearchChunks.contains(chunkKey)) {
            return null;
        }
        int y = ctx.world().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y <= ctx.world().dimensionType().minY() + 1 || y <= ctx.world().getSeaLevel()) {
            return null;
        }
        BetterBlockPos feet = new BetterBlockPos(x, y, z);
        BetterBlockPos ground = feet.below();
        if (isProtected(feet)) {
            return null;
        }
        BlockState groundState = ctx.world().getBlockState(ground);
        if (!(groundState.is(Blocks.GRASS_BLOCK) || groundState.is(Blocks.DIRT) || groundState.is(Blocks.COARSE_DIRT) || groundState.is(Blocks.PODZOL))) {
            return null;
        }
        if (!ctx.world().getBlockState(feet).isAir() || !ctx.world().getBlockState(feet.above()).isAir()) {
            return null;
        }
        if (!ctx.world().getFluidState(feet).isEmpty() || !ctx.world().getFluidState(ground).isEmpty()) {
            return null;
        }
        return feet;
    }

    private boolean isProtected(BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        for (List<AABB> boxes : protectedAreas.values()) {
            for (AABB box : boxes) {
                if (box.contains(center)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void rememberSearch(BetterBlockPos pos) {
        long packed = ChunkPos.pack(pos.x >> 4, pos.z >> 4);
        recentSearchChunks.remove(packed);
        recentSearchChunks.addLast(packed);
        while (recentSearchChunks.size() > RECENT_SEARCH_LIMIT) {
            recentSearchChunks.removeFirst();
        }
    }

    private String formatSummary(String state) {
        Duration runtime = Duration.ofMillis(Math.max(0, System.currentTimeMillis() - startedAt));
        long minutes = runtime.toMinutes();
        long seconds = runtime.minusMinutes(minutes).getSeconds();
        return String.format(
                Locale.US,
                "Cow hunt %s. Kills: %d, loot runs: %d, search hops: %d, distance: %.1fm, runtime: %dm %02ds, protected zones: %d",
                state,
                cowsKilled,
                lootTargetsVisited,
                searchTargetsVisited,
                distanceTravelled,
                minutes,
                seconds,
                protectedZoneCount()
        );
    }

    private int protectedZoneCount() {
        return protectedAreas.values().stream().mapToInt(List::size).sum();
    }
}
