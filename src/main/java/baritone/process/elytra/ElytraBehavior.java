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

package baritone.process.elytra;

import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.behavior.look.IAimProcessor;
import baritone.api.behavior.look.ITickableAimProcessor;
import baritone.api.event.events.*;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.process.ElytraProcess;
import baritone.utils.BlockStateInterface;
import baritone.utils.IRenderer;
import baritone.utils.PathRenderer;
import baritone.utils.accessor.IFireworkRocketEntity;
import com.mojang.blaze3d.vertex.BufferBuilder;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.floats.FloatIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.function.UnaryOperator;

import static baritone.utils.BaritoneMath.fastCeil;
import static baritone.utils.BaritoneMath.fastFloor;

public final class ElytraBehavior implements Helper {
    private static void debug(String message) {
        System.out.println("[Baritone Elytra DBG] " + message);
    }

    private final Baritone baritone;
    private final IPlayerContext ctx;

    // Render stuff
    private final List<Pair<Vec3, Vec3>> clearLines;
    private final List<Pair<Vec3, Vec3>> blockedLines;
    private List<Vec3> simulationLine;
    private BlockPos aimPos;
    private List<BetterBlockPos> visiblePath;

    // :sunglasses:
    private final boolean useNetherPathfinder;
    private final NetherPathfinderContext context;
    public final PathManager pathManager;
    private final ElytraProcess process;

    /**
     * Remaining cool-down ticks between firework usage
     */
    private int remainingFireworkTicks;

    /**
     * Remaining cool-down ticks after the player's position and rotation are reset by the server
     */
    private int remainingSetBackTicks;

    public boolean landingMode;

    /**
     * The most recent minimum number of firework boost ticks, equivalent to {@code 10 * (1 + Flight)}
     * <p>
     * Updated every time a firework is automatically used
     */
    private int minimumBoostTicks;

    private boolean deployedFireworkLastTick;
    private final int[] nextTickBoostCounter;

    private BlockStateInterface bsi;
    private final BlockStateOctreeInterface boi;
    public final BetterBlockPos destination;
    private final boolean appendDestination;
    private final BetterBlockPos landingTarget;
    private final boolean waterLandingTarget;

    private final ExecutorService solverExecutor;
    private Future<Solution> solver;
    private Solution pendingSolution;
    private Solution lastSolution;
    private boolean solveNextTick;

    private long timeLastCacheCull = 0L;

    // auto swap
    private int invTickCountdown = 0;
    private final Queue<Runnable> invTransactionQueue = new LinkedList<>();

    public ElytraBehavior(Baritone baritone, ElytraProcess process, BlockPos destination, boolean appendDestination, BetterBlockPos landingTarget) {
        debug("behavior ctor start dim=" + baritone.getPlayerContext().world().dimension() + " dest=" + destination + " append=" + appendDestination);
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
        this.clearLines = new CopyOnWriteArrayList<>();
        this.blockedLines = new CopyOnWriteArrayList<>();
        this.pathManager = this.new PathManager();
        this.process = process;
        this.destination = new BetterBlockPos(destination);
        this.appendDestination = appendDestination;
        this.landingTarget = landingTarget;
        this.waterLandingTarget = landingTarget != null && !ctx.world().getBlockState(landingTarget).getFluidState().isEmpty();
        this.solverExecutor = Executors.newSingleThreadExecutor();
        this.nextTickBoostCounter = new int[2];
        this.useNetherPathfinder = ctx.world().dimension() == Level.NETHER;
        this.context = this.useNetherPathfinder ? new NetherPathfinderContext(Baritone.settings().elytraNetherSeed.value) : null;
        this.boi = this.context != null ? new BlockStateOctreeInterface(this.context) : null;
        this.bsi = new BlockStateInterface(ctx);
        debug("behavior ctor complete useNetherPathfinder=" + this.useNetherPathfinder);
    }

    public final class PathManager {

        public NetherPath path;
        private boolean completePath;
        private boolean recalculating;

        private int maxPlayerNear;
        private int ticksNearUnchanged;
        private int playerNear;

        public PathManager() {
            // lol imagine initializing fields normally
            this.clear();
        }

        public void tick() {
            // Recalculate closest path node
            this.updatePlayerNear();
            final int prevMaxNear = this.maxPlayerNear;
            this.maxPlayerNear = Math.max(this.maxPlayerNear, this.playerNear);

            if (this.maxPlayerNear == prevMaxNear && ctx.player().isFallFlying()) {
                this.ticksNearUnchanged++;
            } else {
                this.ticksNearUnchanged = 0;
            }

            // Obstacles are more important than an incomplete path, handle those first.
            this.pathfindAroundObstacles();
            this.attemptNextSegment();
        }

        public CompletableFuture<Void> pathToDestination() {
            return this.pathToDestination(ctx.playerFeet());
        }

        public CompletableFuture<Void> pathToDestination(final BlockPos from) {
            debug("pathToDestination from=" + from + " to=" + ElytraBehavior.this.destination + " useNether=" + ElytraBehavior.this.useNetherPathfinder);
            final long start = System.nanoTime();
            return this.path0(from, ElytraBehavior.this.destination, UnaryOperator.identity())
                    .thenRun(() -> {
                        final double distance = this.path.get(0).distanceTo(this.path.get(this.path.size() - 1));
                        if (this.completePath) {
                            logVerbose(String.format("Computed path (%.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        } else {
                            logVerbose(String.format("Computed segment (Next %.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        }
                    })
                    .whenComplete((result, ex) -> {
                        this.recalculating = false;
                        if (ex != null) {
                            final Throwable cause = ex.getCause();
                            if (cause instanceof PathCalculationException) {
                                logDirect("Failed to compute path to destination");
                            } else {
                                logUnhandledException(cause);
                            }
                        }
                    });
        }

        public CompletableFuture<Void> pathRecalcSegment(final OptionalInt upToIncl) {
            if (this.recalculating) {
                throw new IllegalStateException("already recalculating");
            }

            this.recalculating = true;
            final List<BetterBlockPos> after = upToIncl.isPresent() ? this.path.subList(upToIncl.getAsInt() + 1, this.path.size()) : Collections.emptyList();
            final boolean complete = this.completePath;

            return this.path0(ctx.playerFeet(), upToIncl.isPresent() ? this.path.get(upToIncl.getAsInt()) : ElytraBehavior.this.destination, segment -> segment.append(after.stream(), complete || (segment.isFinished() && !upToIncl.isPresent())))
                    .whenComplete((result, ex) -> {
                        this.recalculating = false;
                        if (ex != null) {
                            final Throwable cause = ex.getCause();
                            if (cause instanceof PathCalculationException) {
                                logDirect("Failed to recompute segment");
                            } else {
                                logUnhandledException(cause);
                            }
                        }
                    });
        }

        public void pathNextSegment(final int afterIncl) {
            if (this.recalculating) {
                return;
            }

            this.recalculating = true;
            final List<BetterBlockPos> before = this.path.subList(0, afterIncl + 1);
            final long start = System.nanoTime();
            final BetterBlockPos pathStart = this.path.get(afterIncl);

            this.path0(pathStart, ElytraBehavior.this.destination, segment -> segment.prepend(before.stream()))
                    .thenRun(() -> {
                        final int recompute = this.path.size() - before.size() - 1;
                        final double distance = this.path.get(0).distanceTo(this.path.get(recompute));

                        if (this.completePath) {
                            logVerbose(String.format("Computed path (%.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        } else {
                            logVerbose(String.format("Computed segment (Next %.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        }
                    })
                    .whenComplete((result, ex) -> {
                        this.recalculating = false;
                        if (ex != null) {
                            final Throwable cause = ex.getCause();
                            if (cause instanceof PathCalculationException) {
                                logDirect("Failed to compute next segment");
                                if (ctx.player().distanceToSqr(pathStart.getCenter()) < 16 * 16) {
                                    logVerbose("Player is near the segment start, therefore repeating this calculation is pointless. Marking as complete");
                                    completePath = true;
                                }
                            } else {
                                logUnhandledException(cause);
                            }
                        }
                    });
        }

        public void clear() {
            this.path = NetherPath.emptyPath();
            this.completePath = true;
            this.recalculating = false;
            this.playerNear = 0;
            this.ticksNearUnchanged = 0;
            this.maxPlayerNear = 0;
        }

        private void setPath(final UnpackedSegment segment) {
            List<BetterBlockPos> path = segment.collect();
            if (ElytraBehavior.this.appendDestination && ElytraBehavior.this.useNetherPathfinder) {
                BlockPos dest = ElytraBehavior.this.destination;
                BlockPos last = !path.isEmpty() ? path.get(path.size() - 1) : null;
                if (last != null && ElytraBehavior.this.clearView(Vec3.atLowerCornerOf(dest), Vec3.atLowerCornerOf(last), false)) {
                    path.add(new BetterBlockPos(dest));
                } else {
                    logDirect("unable to land at " + ElytraBehavior.this.destination);
                    process.landingSpotIsBad(new BetterBlockPos(ElytraBehavior.this.destination));
                }
            }
            this.path = new NetherPath(path);
            this.completePath = segment.isFinished();
            this.playerNear = 0;
            this.ticksNearUnchanged = 0;
            this.maxPlayerNear = 0;
        }

        public NetherPath getPath() {
            return this.path;
        }

        public int getNear() {
            return this.playerNear;
        }

        // mickey resigned
        private CompletableFuture<Void> path0(BlockPos src, BlockPos dst, UnaryOperator<UnpackedSegment> operator) {
            debug("path0 start src=" + src + " dst=" + dst + " useNether=" + ElytraBehavior.this.useNetherPathfinder);
            if (ElytraBehavior.this.useNetherPathfinder) {
                return ElytraBehavior.this.context.pathFindAsync(src, dst)
                        .thenApply(UnpackedSegment::from)
                        .thenApply(operator)
                        .thenAcceptAsync(this::setPath, ctx.minecraft()::execute);
            }
            try {
                debug("path0 buildOverworldSegment begin");
                this.setPath(operator.apply(ElytraBehavior.this.buildOverworldSegment(src, dst)));
                debug("path0 buildOverworldSegment complete pathSize=" + this.path.size());
                return CompletableFuture.completedFuture(null);
            } catch (Exception ex) {
                debug("path0 buildOverworldSegment failed " + ex);
                CompletableFuture<Void> failed = new CompletableFuture<>();
                failed.completeExceptionally(ex);
                return failed;
            }
        }

        private void pathfindAroundObstacles() {
            if (this.recalculating) {
                return;
            }

            int rangeStartIncl = playerNear;
            int rangeEndExcl = playerNear;
            while (rangeEndExcl < path.size() && ElytraBehavior.this.hasPathInformation(path.get(rangeEndExcl))) {
                rangeEndExcl++;
            }
            // rangeEndExcl now represents an index either not in the path, or just outside render distance
            if (rangeStartIncl >= rangeEndExcl) {
                // not loaded yet?
                return;
            }
            final BetterBlockPos rangeStart = path.get(rangeStartIncl);
            if (!ElytraBehavior.this.passable(rangeStart.x, rangeStart.y, rangeStart.z, false)) {
                // we're in a wall
                return; // previous iterations of this function SHOULD have fixed this by now :rage_cat:
            }

            if (ElytraBehavior.this.process.state != ElytraProcess.State.LANDING && this.ticksNearUnchanged > 100) {
                this.pathRecalcSegment(OptionalInt.of(rangeEndExcl - 1))
                        .thenRun(() -> {
                            logVerbose("Recalculating segment, no progress in last 100 ticks");
                        });
                this.ticksNearUnchanged = 0;
                return;
            }

            boolean canSeeAny = false;
            for (int i = rangeStartIncl; i < rangeEndExcl - 1; i++) {
                if (ElytraBehavior.this.clearView(ctx.playerFeetAsVec(), this.path.getVec(i), false) || ElytraBehavior.this.clearView(ctx.playerHead(), this.path.getVec(i), false)) {
                    canSeeAny = true;
                }
                if (!ElytraBehavior.this.clearView(this.path.getVec(i), this.path.getVec(i + 1), false)) {
                    // obstacle. where do we return to pathing?
                    // if the end of render distance is closer to goal, then that's fine, otherwise we'd be "digging our hole deeper" and making an already bad backtrack worse
                    OptionalInt rejoinMainPathAt;
                    if (this.path.get(rangeEndExcl - 1).distanceSq(ElytraBehavior.this.destination) < ctx.playerFeet().distanceSq(ElytraBehavior.this.destination)) {
                        rejoinMainPathAt = OptionalInt.of(rangeEndExcl - 1); // rejoin after current render distance
                    } else {
                        rejoinMainPathAt = OptionalInt.empty(); // large backtrack detected. ignore render distance, rejoin later on
                    }

                    final BetterBlockPos blockage = this.path.get(i);
                    final double distance = ctx.playerFeet().distanceTo(this.path.get(rejoinMainPathAt.orElse(path.size() - 1)));

                    final long start = System.nanoTime();
                    this.pathRecalcSegment(rejoinMainPathAt)
                            .thenRun(() -> {
                                logVerbose(String.format("Recalculated segment around path blockage near %s %s %s (next %.1f blocks in %.4f seconds)",
                                        SettingsUtil.maybeCensor(blockage.x),
                                        SettingsUtil.maybeCensor(blockage.y),
                                        SettingsUtil.maybeCensor(blockage.z),
                                        distance,
                                        (System.nanoTime() - start) / 1e9d
                                ));
                            });
                    return;
                }
            }
            if (!canSeeAny && rangeStartIncl < rangeEndExcl - 2 && process.state != ElytraProcess.State.GET_TO_JUMP) {
                this.pathRecalcSegment(OptionalInt.of(rangeEndExcl - 1)).thenRun(() -> logVerbose("Recalculated segment since no path points were visible"));
            }
        }

        private void attemptNextSegment() {
            if (this.recalculating) {
                return;
            }

            final int last = this.path.size() - 1;
            if (!this.completePath && ElytraBehavior.this.hasPathInformation(this.path.get(last))) {
                this.pathNextSegment(last);
            }
        }

        public void updatePlayerNear() {
            if (this.path.isEmpty()) {
                return;
            }

            int index = this.playerNear;
            final BetterBlockPos pos = ctx.playerFeet();
            for (int i = index; i >= Math.max(index - 1000, 0); i -= 10) {
                if (ElytraBehavior.this.pathDistanceSq(path.get(i), pos) < ElytraBehavior.this.pathDistanceSq(path.get(index), pos)) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            for (int i = index; i < Math.min(index + 1000, path.size()); i += 10) {
                if (ElytraBehavior.this.pathDistanceSq(path.get(i), pos) < ElytraBehavior.this.pathDistanceSq(path.get(index), pos)) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            for (int i = index; i >= Math.max(index - 50, 0); i--) {
                if (ElytraBehavior.this.pathDistanceSq(path.get(i), pos) < ElytraBehavior.this.pathDistanceSq(path.get(index), pos)) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            for (int i = index; i < Math.min(index + 50, path.size()); i++) {
                if (ElytraBehavior.this.pathDistanceSq(path.get(i), pos) < ElytraBehavior.this.pathDistanceSq(path.get(index), pos)) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            this.playerNear = index;
        }

        public boolean isComplete() {
            return this.completePath;
        }
    }

    public boolean usesNetherPathfinder() {
        return this.useNetherPathfinder;
    }

    public BetterBlockPos getLandingTarget() {
        return this.landingTarget;
    }

    public boolean shouldBeginLanding() {
        if (this.landingTarget == null) {
            return false;
        }
        final double horizontalSpeed = ctx.player().getDeltaMovement().multiply(1, 0, 1).length();
        final double downwardSpeed = Math.max(0.0D, -ctx.player().getDeltaMovement().y);
        final Vec3 landingCenter = this.getLandingPadCenter();
        final double altitudeAbovePad = landingCenter == null ? 0.0D : Math.max(0.0D, ctx.player().position().y - landingCenter.y);
        final double leadDistance = this.waterLandingTarget
                ? Mth.clamp(horizontalSpeed * 32.0D + downwardSpeed * 20.0D + Math.max(0.0D, altitudeAbovePad - 6.0D) * 0.45D + 28.0D, 36.0D, 128.0D)
                : Mth.clamp(horizontalSpeed * 26.0D + downwardSpeed * 18.0D + Math.max(0.0D, altitudeAbovePad - 6.0D) * 0.35D + 18.0D, 28.0D, 96.0D);
        return this.horizontalDistanceSq(ctx.player().position(), this.destination.getCenter()) < leadDistance * leadDistance;
    }

    public long getNetherSeed() {
        if (this.context == null) {
            throw new IllegalStateException("No nether context is active");
        }
        return this.context.getSeed();
    }

    private boolean hasPathInformation(BetterBlockPos pos) {
        if (this.useNetherPathfinder) {
            return this.context.hasChunk(ChunkPos.containing(pos));
        }
        return this.bsi.isLoaded(pos.x, pos.z);
    }

    private double pathDistanceSq(final BetterBlockPos node, final BetterBlockPos pos) {
        if (this.useNetherPathfinder) {
            return node.distanceSq(pos);
        }
        final double dx = node.x - pos.x;
        final double dz = node.z - pos.z;
        return dx * dx + dz * dz;
    }

    private UnpackedSegment buildOverworldSegment(final BlockPos src, final BlockPos dst) {
        debug("buildOverworldSegment start src=" + src + " dst=" + dst);
        final int minY = ctx.world().dimensionType().minY();
        final int maxCruiseY = minY + ctx.world().dimensionType().height() - 17;
        final int requestedCruiseY = Mth.clamp(Baritone.settings().elytraOverworldCruiseY.value, minY + 16, maxCruiseY);
        debug("buildOverworldSegment requestedCruise=" + requestedCruiseY);
        List<BetterBlockPos> path = this.buildCruisePath(src, dst, requestedCruiseY, maxCruiseY);
        debug("buildOverworldSegment pathNodes=" + path.size() + " first=" + (path.isEmpty() ? "null" : path.get(0)) + " last=" + (path.isEmpty() ? "null" : path.get(path.size() - 1)));
        return new UnpackedSegment(path.stream(), true);
    }

    private List<BetterBlockPos> buildCruisePath(final BlockPos src, final BlockPos dst, final int requestedCruiseY, final int maxCruiseY) {
        debug("buildCruisePath start requestedCruiseY=" + requestedCruiseY);
        final int spacing = Math.max(16, Baritone.settings().elytraOverworldWaypointDistance.value);
        final List<BetterBlockPos> raw = new ArrayList<>();
        raw.add(new BetterBlockPos(src));

        final double dx = dst.getX() - src.getX();
        final double dz = dst.getZ() - src.getZ();
        final double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDistance > 1.0D) {
            final double ascentDistance = Math.min(horizontalDistance, Math.max(24.0D, spacing * 0.5D));
            final double ascentT = Math.min(1.0D, ascentDistance / horizontalDistance);
            raw.add(this.buildCruiseWaypoint(src, dst, ascentT, requestedCruiseY, maxCruiseY, spacing));

            final double finalApproachDistance = Math.min(horizontalDistance, Math.max(32.0D, spacing));
            final double cruiseEnd = Math.max(ascentDistance, horizontalDistance - finalApproachDistance);
            for (double travelled = spacing; travelled < cruiseEnd; travelled += spacing) {
                final double t = travelled / horizontalDistance;
                raw.add(this.buildCruiseWaypoint(src, dst, t, requestedCruiseY, maxCruiseY, spacing));
            }

            final double approachT = horizontalDistance <= finalApproachDistance
                    ? 1.0D
                    : Mth.clamp((horizontalDistance - finalApproachDistance) / horizontalDistance, ascentT, 1.0D);
            raw.add(this.buildApproachWaypoint(src, dst, approachT, horizontalDistance, requestedCruiseY, maxCruiseY, spacing));
        }

        raw.add(new BetterBlockPos(dst));

        final List<BetterBlockPos> deduped = new ArrayList<>(raw.size());
        BetterBlockPos previous = null;
        for (BetterBlockPos point : raw) {
            if (!Objects.equals(previous, point)) {
                deduped.add(point);
                previous = point;
            }
        }
        debug("buildCruisePath complete spacing=" + spacing + " raw=" + raw.size() + " deduped=" + deduped.size());
        return deduped;
    }

    private BetterBlockPos buildCruiseWaypoint(final BlockPos src, final BlockPos dst, final double t, final int requestedCruiseY, final int maxCruiseY, final int spacing) {
        final int x = fastFloor(Mth.lerp(t, src.getX(), dst.getX()));
        final int z = fastFloor(Mth.lerp(t, src.getZ(), dst.getZ()));
        final int y = this.computeLocalCruiseY(src, dst, t, requestedCruiseY, maxCruiseY, spacing);
        return new BetterBlockPos(x, y, z);
    }

    private BetterBlockPos buildApproachWaypoint(final BlockPos src, final BlockPos dst, final double t, final double horizontalDistance, final int requestedCruiseY, final int maxCruiseY, final int spacing) {
        final int x = fastFloor(Mth.lerp(t, src.getX(), dst.getX()));
        final int z = fastFloor(Mth.lerp(t, src.getZ(), dst.getZ()));
        final int localCruiseY = this.computeLocalCruiseY(src, dst, t, requestedCruiseY, maxCruiseY, spacing);
        return new BetterBlockPos(x, this.computeOverworldApproachY(dst, localCruiseY, horizontalDistance), z);
    }

    private int computeLocalCruiseY(final BlockPos src, final BlockPos dst, final double t, final int requestedCruiseY, final int maxCruiseY, final int spacing) {
        final int minY = ctx.world().dimensionType().minY();
        final int clearance = Baritone.settings().elytraOverworldTerrainClearance.value;
        final int localTerrainY = this.sampleLocalTerrainMaxY(src, dst, t, spacing);
        return Mth.clamp(Math.max(requestedCruiseY, localTerrainY + clearance), minY + 16, maxCruiseY);
    }

    private int computeOverworldApproachY(final BlockPos dst, final int cruiseY, final double horizontalDistance) {
        final int approachOffset = Mth.clamp(fastCeil(horizontalDistance * 0.22D), 20, 48);
        return Math.min(cruiseY, dst.getY() + approachOffset);
    }

    private int sampleLocalTerrainMaxY(final BlockPos src, final BlockPos dst, final double centerT, final int spacing) {
        final double dx = dst.getX() - src.getX();
        final double dz = dst.getZ() - src.getZ();
        final double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDistance <= 1.0D) {
            return this.defaultTerrainReferenceY();
        }
        final double windowDistance = Math.max(24.0D, spacing * 1.5D);
        final double centerDistance = Mth.clamp(horizontalDistance * centerT, 0.0D, horizontalDistance);
        final double startDistance = Math.max(0.0D, centerDistance - windowDistance * 0.5D);
        final double endDistance = Math.min(horizontalDistance, centerDistance + windowDistance * 0.5D);
        final int samples = Math.max(1, fastCeil((endDistance - startDistance) / 8.0D));
        int maxTerrainY = Integer.MIN_VALUE;
        boolean foundLoadedTerrain = false;
        boolean unknownTerrain = false;
        for (int i = 0; i <= samples; i++) {
            final double travelled = Mth.lerp(i / (double) samples, startDistance, endDistance);
            final double t = travelled / horizontalDistance;
            final int x = fastFloor(src.getX() + dx * t);
            final int z = fastFloor(src.getZ() + dz * t);
            Integer terrainTopY = this.getTerrainTopYIfLoaded(x, z);
            if (terrainTopY == null) {
                unknownTerrain = true;
                continue;
            }
            foundLoadedTerrain = true;
            maxTerrainY = Math.max(maxTerrainY, terrainTopY);
        }
        if (!foundLoadedTerrain) {
            return this.defaultTerrainReferenceY();
        }
        if (unknownTerrain) {
            maxTerrainY += Math.max(4, Baritone.settings().elytraOverworldTerrainClearance.value / 2);
        }
        return maxTerrainY;
    }

    private int defaultTerrainReferenceY() {
        final int minY = ctx.world().dimensionType().minY();
        final int maxY = minY + ctx.world().dimensionType().height() - 1;
        return Mth.clamp(ctx.world().getSeaLevel() + 1, minY + 1, maxY);
    }

    private Integer getTerrainTopYIfLoaded(final int x, final int z) {
        if (!this.bsi.isLoaded(x, z)) {
            return null;
        }
        return ctx.world().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    public void onRenderPass(RenderEvent event) {

        final Settings settings = Baritone.settings();
        if (this.visiblePath != null) {
            PathRenderer.drawPath(event.getModelViewStack(), this.visiblePath, 0, Color.RED, false, 0, 0, 0.0D);
        }
        if (this.aimPos != null) {
            PathRenderer.drawGoal(event.getModelViewStack(), ctx, new GoalBlock(this.aimPos), event.getPartialTicks(), Color.GREEN);
        }
        if (!this.clearLines.isEmpty() && settings.elytraRenderRaytraces.value) {
            BufferBuilder bufferBuilder = IRenderer.startLines(Color.GREEN);
            for (Pair<Vec3, Vec3> line : this.clearLines) {
                IRenderer.emitLine(bufferBuilder, event.getModelViewStack(), line.first(), line.second(), settings.pathRenderLineWidthPixels.value);
            }
            IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
        }
        if (!this.blockedLines.isEmpty() && Baritone.settings().elytraRenderRaytraces.value) {
            BufferBuilder bufferBuilder = IRenderer.startLines(Color.BLUE);
            for (Pair<Vec3, Vec3> line : this.blockedLines) {
                IRenderer.emitLine(bufferBuilder, event.getModelViewStack(), line.first(), line.second(), settings.pathRenderLineWidthPixels.value);
            }
            IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
        }
        if (this.simulationLine != null && Baritone.settings().elytraRenderSimulation.value) {
            BufferBuilder bufferBuilder = IRenderer.startLines(new Color(0x36CCDC));
            final Vec3 offset = ctx.player().getPosition(event.getPartialTicks());
            for (int i = 0; i < this.simulationLine.size() - 1; i++) {
                final Vec3 src = this.simulationLine.get(i).add(offset);
                final Vec3 dst = this.simulationLine.get(i + 1).add(offset);
                IRenderer.emitLine(bufferBuilder, event.getModelViewStack(), src, dst, settings.pathRenderLineWidthPixels.value);
            }
            IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
        }
    }

    public void onChunkEvent(ChunkEvent event) {
        if (event.isPostPopulate() && this.context != null) {
            final LevelChunk chunk = ctx.world().getChunk(event.getX(), event.getZ());
            this.context.queueForPacking(chunk);
        }
    }

    public void onBlockChange(BlockChangeEvent event) {
        if (this.context != null) {
            this.context.queueBlockUpdate(event);
        }
    }

    public void onReceivePacket(PacketEvent event) {
        if (event.getPacket() instanceof ClientboundPlayerPositionPacket) {
            ctx.minecraft().execute(() -> {
                this.remainingSetBackTicks = Baritone.settings().elytraFireworkSetbackUseDelay.value;
            });
        }
    }

    public void pathTo() {
        debug("behavior pathTo autoJump=" + Baritone.settings().elytraAutoJump.value + " isFallFlying=" + ctx.player().isFallFlying());
        if (!this.shouldAutoTakeoff() || ctx.player().isFallFlying()) {
            this.pathManager.pathToDestination();
        }
    }

    private boolean shouldAutoTakeoff() {
        return this.ctx.world().dimension() != Level.NETHER || Baritone.settings().elytraAutoJump.value;
    }

    public void destroy() {
        if (this.solver != null) {
            this.solver.cancel(true);
        }
        this.solverExecutor.shutdown();
        try {
            while (!this.solverExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {}
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if (this.context != null) {
            this.context.destroy();
        }
    }

    public void repackChunks() {
        if (this.context == null) {
            return;
        }
        ChunkSource chunkProvider = ctx.world().getChunkSource();

        BetterBlockPos playerPos = ctx.playerFeet();

        int playerChunkX = playerPos.getX() >> 4;
        int playerChunkZ = playerPos.getZ() >> 4;

        int minX = playerChunkX - 40;
        int minZ = playerChunkZ - 40;
        int maxX = playerChunkX + 40;
        int maxZ = playerChunkZ + 40;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                LevelChunk chunk = chunkProvider.getChunk(x, z, false);

                if (chunk != null && !chunk.isEmpty()) {
                    this.context.queueForPacking(chunk);
                }
            }
        }
    }

    public void onTick() {
        if (this.context != null) {
            synchronized (this.context.cullingLock) {
                this.onTick0();
            }
        } else {
            this.onTick0();
        }
        final long now = System.currentTimeMillis();
        if (this.context != null && (now - this.timeLastCacheCull) / 1000 > Baritone.settings().elytraTimeBetweenCacheCullSecs.value) {
            this.context.queueCacheCulling(ctx.player().chunkPosition().x(), ctx.player().chunkPosition().z(), Baritone.settings().elytraCacheCullDistance.value, this.boi);
            this.timeLastCacheCull = now;
        }
    }

    private void onTick0() {
        try {
            debug("onTick0 start pathSize=" + this.pathManager.getPath().size() + " state=" + this.process.state);
            this.pendingSolution = null;
            if (this.solver != null) {
                if (this.solver.isDone()) {
                    try {
                        this.pendingSolution = this.solver.get();
                        if (this.pendingSolution != null) {
                            this.lastSolution = this.pendingSolution;
                        }
                    } catch (Exception ignored) {
                        // ignore solver failures; a later async solve can replace the cached solution
                    } finally {
                        this.solver = null;
                    }
                } else if (this.landingMode) {
                    this.pendingSolution = this.lastSolution;
                }
            }

            tickInventoryTransactions();

            // Certified mojang employee incident
            if (this.remainingFireworkTicks > 0) {
                this.remainingFireworkTicks--;
            }
            if (this.remainingSetBackTicks > 0) {
                this.remainingSetBackTicks--;
            }
            if (!this.getAttachedFirework().isPresent()) {
                this.minimumBoostTicks = 0;
            }

            // Reset rendered elements
            this.clearLines.clear();
            this.blockedLines.clear();
            this.visiblePath = null;
            this.simulationLine = null;
            this.aimPos = null;

            final List<BetterBlockPos> path = this.pathManager.getPath();
            if (path.isEmpty()) {
                debug("onTick0 abort empty path");
                return;
            } else if (this.destination == null) {
                debug("onTick0 abort null destination");
                this.pathManager.clear();
                return;
            }

            // ctx AND context???? :DDD
            this.bsi = new BlockStateInterface(ctx);
            debug("onTick0 bsi refreshed");
            if (this.useNetherPathfinder) {
                debug("onTick0 pathManager.tick begin");
                this.pathManager.tick();
                debug("onTick0 pathManager.tick complete");
            } else {
                debug("onTick0 overworld updatePlayerNear begin");
                this.pathManager.updatePlayerNear();
                debug("onTick0 overworld updatePlayerNear complete");
            }

            final int playerNear = this.pathManager.getNear();
            this.visiblePath = path.subList(
                    Math.max(playerNear - 30, 0),
                    Math.min(playerNear + 100, path.size())
            );
            debug("onTick0 complete playerNear=" + playerNear + " visiblePath=" + this.visiblePath.size());
        } catch (Throwable t) {
            debug("onTick0 threw " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace();
            this.pathManager.clear();
        }
    }

    /**
     * Called by {@link baritone.process.ElytraProcess#onTick(boolean, boolean)} when the process is in control and the player is flying
     */
    public void tick() {
        try {
            debug("tick start pathSize=" + this.pathManager.getPath().size() + " fallFlying=" + ctx.player().isFallFlying());
            if (this.pathManager.getPath().isEmpty()) {
                debug("tick abort empty path");
                return;
            }

            trySwapElytra();

            if (ctx.player().horizontalCollision) {
                logVerbose("hbonk");
            }
            if (ctx.player().verticalCollision) {
                logVerbose("vbonk");
            }

            final SolverContext solverContext = this.new SolverContext(false);
            this.solveNextTick = true;

            final Solution solution;
            final Solution reusable = this.canReuseSolution(this.pendingSolution, solverContext)
                    ? this.pendingSolution
                    : this.canReuseSolution(this.lastSolution, solverContext)
                    ? this.lastSolution
                    : null;
            if (reusable != null) {
                if (reusable == this.pendingSolution) {
                    debug("tick using pending solution");
                } else {
                    debug("tick using cached solution");
                }
                solution = reusable;
            } else if (this.landingMode) {
                debug("tick awaiting async landing solution");
                return;
            } else {
                debug("tick solving sync");
                solution = this.solveAngles(solverContext);
                if (solution != null) {
                    this.lastSolution = solution;
                }
            }

            if (this.deployedFireworkLastTick) {
                this.nextTickBoostCounter[solverContext.boost.isBoosted() ? 1 : 0]++;
                this.deployedFireworkLastTick = false;
            }

            final boolean inLava = ctx.player().isInLava();
            if (inLava) {
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            }

            if (solution == null) {
                logVerbose("no solution");
                debug("tick abort null solution");
                return;
            }

            baritone.getLookBehavior().updateTarget(solution.rotation, false);
            debug("tick solution goingTo=" + solution.goingTo + " solvedPitch=" + solution.solvedPitch + " forceFw=" + solution.forceUseFirework);

            if (!solution.solvedPitch) {
                logVerbose("no pitch solution, probably gonna crash in a few ticks LOL!!!");
                return;
            } else {
                this.aimPos = new BetterBlockPos(solution.goingTo.x, solution.goingTo.y, solution.goingTo.z);
            }

            this.tickUseFireworks(
                    solution.context.start,
                    solution.goingTo,
                    solution.context.boost.isBoosted(),
                    solution.forceUseFirework || inLava
            );
        } catch (Throwable t) {
            debug("tick threw " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace();
            this.pathManager.clear();
        }
    }

    private int selectOverworldTargetIndex(final NetherPath path, final int playerNear, final boolean landing) {
        if (path.isEmpty()) {
            return 0;
        }
        final int finalIndex = path.size() - 1;
        if (landing) {
            final int approachIndex = Math.max(0, finalIndex - 1);
            final Vec3 landingTarget = this.getOverworldLandingControlPoint(path);
            final double horizontalDistance = this.horizontalDistanceSq(ctx.player().position(), landingTarget);
            final double verticalOffset = ctx.player().position().y - landingTarget.y;
            if (horizontalDistance < 6 * 6 || verticalOffset < 6.0D) {
                return finalIndex;
            }
            return approachIndex;
        }
        final Vec3 finalTarget = this.getOverworldTarget(path, finalIndex);
        final double finalDistanceSq = this.horizontalDistanceSq(ctx.player().position(), finalTarget);
        if (path.size() >= 2 && finalDistanceSq < 48 * 48) {
            return finalIndex;
        }
        final boolean finalApproach = this.appendDestination
                || finalDistanceSq < Math.max(24 * 24, Baritone.settings().elytraOverworldWaypointDistance.value * Baritone.settings().elytraOverworldWaypointDistance.value)
                || playerNear >= Math.max(0, this.getOverworldCruiseIndex(path) - 1);
        final int maxIndex = finalApproach ? finalIndex : this.getOverworldCruiseIndex(path);
        final int firstCandidate = Math.min(playerNear + 1, maxIndex);
        int best = firstCandidate;
        final Vec3 eye = ctx.playerHead();
        final Vec3 feet = ctx.playerFeetAsVec();
        for (int i = firstCandidate; i <= Math.min(maxIndex, firstCandidate + 4); i++) {
            final Vec3 candidate = this.getOverworldTarget(path, i);
            if (eye.distanceToSqr(candidate) < 16 * 16) {
                best = i;
                continue;
            }
            if (this.clearView(eye, candidate, false) || this.clearView(feet, candidate, false)) {
                best = i;
                continue;
            }
            break;
        }
        if (path.size() >= 2 && best >= maxIndex && finalApproach && (this.clearView(eye, finalTarget, false) || this.clearView(feet, finalTarget, false))) {
            return finalIndex;
        }
        return best;
    }

    private Vec3 getOverworldTarget(final NetherPath path, final int index) {
        final BetterBlockPos pos = path.get(index);
        return Vec3.atCenterOf(new BlockPos(pos.x, pos.y, pos.z));
    }

    private Vec3 getOverworldLandingControlPoint(final NetherPath path) {
        if (this.landingTarget != null) {
            final double controlY = this.waterLandingTarget ? this.landingTarget.y + 8.0D : this.landingTarget.y + 3.0D;
            return new Vec3(this.landingTarget.x + 0.5D, controlY, this.landingTarget.z + 0.5D);
        }
        return this.getOverworldTarget(path, path.size() - 1);
    }

    private Vec3 getLandingPadCenter() {
        if (this.landingTarget == null) {
            return null;
        }
        final double landingY = this.waterLandingTarget ? this.landingTarget.y + 0.5D : this.landingTarget.y + 1.0D;
        return new Vec3(this.landingTarget.x + 0.5D, landingY, this.landingTarget.z + 0.5D);
    }

    private LandingProfile buildLandingProfile(final SolverContext context) {
        final Vec3 landingCenter = this.getLandingPadCenter();
        if (landingCenter == null) {
            return null;
        }
        final Vec3 toPad = new Vec3(landingCenter.x - context.start.x, 0.0D, landingCenter.z - context.start.z);
        Vec3 runwayDir = context.motion.multiply(1.0D, 0.0D, 1.0D);
        if (runwayDir.lengthSqr() < 0.25D * 0.25D) {
            runwayDir = toPad;
        }
        if (runwayDir.lengthSqr() < 1.0E-6D) {
            runwayDir = new Vec3(1.0D, 0.0D, 0.0D);
        } else {
            runwayDir = runwayDir.normalize();
        }
        final double horizontalSpeed = context.motion.multiply(1.0D, 0.0D, 1.0D).length();
        final double downwardSpeed = Math.max(0.0D, -context.motion.y);
        final double altitudeAbovePad = Math.max(0.0D, context.start.y - landingCenter.y);
        final double currentDistance = Math.sqrt(this.horizontalDistanceSq(context.start, landingCenter));
        final double prepDistance = Mth.clamp(horizontalSpeed * 26.0D + downwardSpeed * 18.0D + Math.max(0.0D, altitudeAbovePad - 6.0D) * 0.35D + 18.0D, 28.0D, 96.0D);
        final double approachDistance = Mth.clamp(horizontalSpeed * 16.0D + downwardSpeed * 12.0D + altitudeAbovePad * 0.15D + 12.0D, 16.0D, 52.0D);
        final double commitDistance = Mth.clamp(horizontalSpeed * 7.0D + downwardSpeed * 6.0D + 4.0D, 4.0D, 18.0D);
        final double flareHeight = Mth.clamp(3.5D + horizontalSpeed * 2.5D + downwardSpeed * 8.0D, 4.0D, 12.0D);
        final double approachHeight = Mth.clamp(flareHeight + 6.0D + horizontalSpeed * 3.0D + downwardSpeed * 8.0D, flareHeight + 4.0D, 26.0D);
        return new LandingProfile(landingCenter, runwayDir, horizontalSpeed, downwardSpeed, altitudeAbovePad, currentDistance, prepDistance, approachDistance, commitDistance, approachHeight, flareHeight);
    }

    private Vec3 buildLandingControlTarget(final LandingProfile profile, final double shortDistance, final double height) {
        return profile.landingCenter.subtract(profile.runwayDir.scale(shortDistance)).add(0.0D, height, 0.0D);
    }

    private List<LandingCandidate> buildLandingApproachCandidates(final LandingProfile profile) {
        final List<LandingCandidate> candidates = new ArrayList<>(3);
        final double extendDistance = Math.min(profile.prepDistance, profile.approachDistance + 12.0D);
        candidates.add(new LandingCandidate(this.buildLandingControlTarget(profile, extendDistance, Math.min(26.0D, profile.approachHeight + 5.0D)), extendDistance, Math.min(26.0D, profile.approachHeight + 5.0D)));
        candidates.add(new LandingCandidate(this.buildLandingControlTarget(profile, profile.approachDistance, profile.approachHeight), profile.approachDistance, profile.approachHeight));
        final double transitionDistance = Math.max(profile.commitDistance + 6.0D, profile.approachDistance - 8.0D);
        final double transitionHeight = Math.max(profile.flareHeight + 4.0D, profile.approachHeight - 5.0D);
        candidates.add(new LandingCandidate(this.buildLandingControlTarget(profile, transitionDistance, transitionHeight), transitionDistance, transitionHeight));
        return candidates;
    }

    private List<LandingCandidate> buildLandingTouchdownCandidates(final LandingProfile profile) {
        final List<LandingCandidate> candidates = new ArrayList<>(3);
        final double highShortDistance = Math.max(profile.commitDistance + 4.0D, 6.0D);
        candidates.add(new LandingCandidate(this.buildLandingControlTarget(profile, highShortDistance, profile.flareHeight + 3.0D), highShortDistance, profile.flareHeight + 3.0D));
        candidates.add(new LandingCandidate(this.buildLandingControlTarget(profile, profile.commitDistance, profile.flareHeight), profile.commitDistance, profile.flareHeight));
        final double finalShortDistance = Math.max(2.5D, profile.commitDistance * 0.5D);
        candidates.add(new LandingCandidate(this.buildLandingControlTarget(profile, finalShortDistance, Math.max(2.5D, profile.flareHeight - 1.0D)), finalShortDistance, Math.max(2.5D, profile.flareHeight - 1.0D)));
        return candidates;
    }

    private Vec3 getOverworldControlTarget(final NetherPath path, final int targetIndex, final boolean landing) {
        if (landing && targetIndex == path.size() - 1) {
            return this.getOverworldLandingControlPoint(path);
        }
        if (!landing) {
            return this.getOverworldCruiseControlPoint(path, targetIndex);
        }
        return this.getOverworldTarget(path, targetIndex);
    }

    private Vec3 getOverworldCruiseControlPoint(final NetherPath path, final int targetIndex) {
        if (targetIndex <= 0) {
            return this.getOverworldTarget(path, targetIndex);
        }

        final Vec3 start = this.getOverworldTarget(path, targetIndex - 1);
        final Vec3 end = this.getOverworldTarget(path, targetIndex);
        final Vec3 delta = end.subtract(start);
        final double length = delta.length();
        if (length < 1.0E-3D) {
            return end;
        }

        final Vec3 direction = delta.scale(1.0D / length);
        final double leadDistance = Math.min(length, Math.max(8.0D, Baritone.settings().elytraOverworldWaypointDistance.value * 0.5D));
        final double projection = Mth.clamp(ctx.player().position().subtract(start).dot(direction), 0.0D, length);
        return start.add(direction.scale(Math.min(length, projection + leadDistance)));
    }

    private int getOverworldCruiseIndex(final NetherPath path) {
        if (path.size() <= 1) {
            return 0;
        }
        return path.size() - 2;
    }

    private boolean canReuseSolution(final Solution solution, final SolverContext context) {
        if (solution == null) {
            return false;
        }
        final SolverContext prior = solution.context;
        if (prior.path != context.path || prior.landingMode != context.landingMode) {
            return false;
        }
        if (Math.abs(prior.playerNear - context.playerNear) > 1) {
            return false;
        }
        if (prior.start.distanceToSqr(context.start) > (context.landingMode ? 64.0D : 16.0D)) {
            return false;
        }
        if (prior.motion.subtract(context.motion).lengthSqr() > (context.landingMode ? 0.20D : 0.08D)) {
            return false;
        }
        if (!context.landingMode) {
            return prior.boost.equals(context.boost) && prior.ignoreLava == context.ignoreLava;
        }
        return true;
    }

    private double computeOverworldDesiredAltitude(final NetherPath path, final int targetIndex, final Vec3 target, final boolean landing) {
        final int finalIndex = path.size() - 1;
        if (landing) {
            final Vec3 landingTarget = this.getOverworldLandingControlPoint(path);
            final double horizontalDistance = Math.sqrt(this.horizontalDistanceSq(ctx.player().position(), landingTarget));
            final double glideAltitude = landingTarget.y + Math.min(40.0D, horizontalDistance * 0.35D + 6.0D);
            if (targetIndex == finalIndex) {
                return Math.max(landingTarget.y + 1.5D, glideAltitude);
            }
            return Math.max(target.y, glideAltitude + 8.0D);
        }
        final double terrainSafeAltitude = this.sampleTerrainAhead(target);
        final double horizontalSpeed = ctx.player().getDeltaMovement().multiply(1.0D, 0.0D, 1.0D).length();
        final double downwardSpeed = Math.max(0.0D, -ctx.player().getDeltaMovement().y);
        final double climbBuffer = Mth.clamp(2.0D + horizontalSpeed * 1.5D + downwardSpeed * 10.0D, 2.0D, 8.0D);
        return Math.max(target.y, terrainSafeAltitude + climbBuffer);
    }

    private double sampleTerrainAhead(final Vec3 target) {
        final Vec3 start = ctx.playerFeetAsVec();
        final double dx = target.x - start.x;
        final double dz = target.z - start.z;
        final double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDistance <= 1.0D) {
            return ctx.player().position().y;
        }

        final double horizontalSpeed = ctx.player().getDeltaMovement().multiply(1.0D, 0.0D, 1.0D).length();
        final double requestedLookahead = Math.max(24.0D, Baritone.settings().elytraOverworldTerrainLookahead.value);
        final double speedLookahead = 24.0D + horizontalSpeed * 22.0D;
        final double lookahead = Mth.clamp(Math.max(horizontalDistance, Math.max(requestedLookahead, speedLookahead)), 24.0D, 128.0D);
        final double dirX = dx / horizontalDistance;
        final double dirZ = dz / horizontalDistance;
        final int clearance = Baritone.settings().elytraOverworldTerrainClearance.value;
        final int steps = Math.max(1, fastCeil(lookahead / 8.0D));
        int maxTerrainY = Integer.MIN_VALUE;
        boolean foundLoadedTerrain = false;
        boolean unknownTerrain = false;
        for (int i = 1; i <= steps; i++) {
            final double distance = lookahead * (i / (double) steps);
            final int x = fastFloor(start.x + dirX * distance);
            final int z = fastFloor(start.z + dirZ * distance);
            Integer terrainTopY = this.getTerrainTopYIfLoaded(x, z);
            if (terrainTopY == null) {
                unknownTerrain = true;
                continue;
            }
            foundLoadedTerrain = true;
            maxTerrainY = Math.max(maxTerrainY, terrainTopY);
        }
        if (!foundLoadedTerrain) {
            return ctx.player().position().y;
        }
        if (unknownTerrain) {
            maxTerrainY += Math.max(4, clearance / 2);
        }
        return maxTerrainY + clearance;
    }

    private float computeOverworldPitch(final float basePitch, final double desiredAltitude, final boolean landing) {
        final double altitudeError = desiredAltitude - ctx.player().position().y;
        final double verticalVelocity = ctx.player().getDeltaMovement().y;
        if (landing) {
            final float correction = (float) Mth.clamp(altitudeError * 0.34D - verticalVelocity * 14.0D, -24.0D, 20.0D);
            return Mth.clamp(basePitch - correction, -28.0F, 22.0F);
        }
        final float correction = (float) Mth.clamp(altitudeError * 0.40D - verticalVelocity * 14.0D, -24.0D, 24.0D);
        return Mth.clamp(basePitch - correction, -45.0F, 35.0F);
    }

    private boolean shouldForceOverworldFirework(final double desiredAltitude, final Vec3 target, final boolean isBoosted, final boolean landing) {
        if (landing) {
            return false;
        }
        if (isBoosted) {
            return false;
        }
        final double altitudeError = desiredAltitude - ctx.player().position().y;
        final double terrainClearance = ctx.player().position().y - (this.sampleTerrainAhead(target) - Baritone.settings().elytraOverworldTerrainClearance.value);
        final double verticalVelocity = ctx.player().getDeltaMovement().y;
        final double horizontalSpeed = ctx.player().getDeltaMovement().multiply(1, 0, 1).length();
        final double fireworkSpeed = Baritone.settings().elytraFireworkSpeed.value;
        final boolean severeTerrainRisk = terrainClearance < Math.max(4, Baritone.settings().elytraOverworldTerrainClearance.value / 3) && verticalVelocity < -0.20D;
        final boolean badlyBelowCruise = altitudeError > 18.0D && verticalVelocity < -0.25D && horizontalSpeed < fireworkSpeed * 1.15D;
        if (!Baritone.settings().elytraConserveFireworks.value) {
            return severeTerrainRisk || badlyBelowCruise;
        }
        return severeTerrainRisk;
    }

    private double horizontalDistanceSq(final Vec3 first, final Vec3 second) {
        final double dx = first.x - second.x;
        final double dz = first.z - second.z;
        return dx * dx + dz * dz;
    }

    public void onPostTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.IN && this.solveNextTick) {
            if (this.solver != null && !this.solver.isDone()) {
                return;
            }
            // We're at the end of the tick, the player's position likely updated and the closest path node could've
            // changed. Updating it now will avoid unnecessary recalculation on the main thread.
            this.pathManager.updatePlayerNear();

            final SolverContext context = this.new SolverContext(true);
            this.solver = this.solverExecutor.submit(() -> this.solveAngles(context));
            this.solveNextTick = false;
        }
    }

    private Solution solveAngles(final SolverContext context) {
        if (!this.useNetherPathfinder) {
            return this.solveOverworldAngles(context);
        }
        return this.solveNetherAngles(context);
    }

    private Solution solveOverworldAngles(final SolverContext context) {
        final NetherPath path = context.path;
        if (path.isEmpty()) {
            return null;
        }

        if (context.landingMode && this.landingTarget != null) {
            return this.solveOverworldLandingAngles(context, path);
        }

        final int targetIndex = this.selectOverworldTargetIndex(path, context.playerNear, context.landingMode);
        final Vec3 target = this.getOverworldControlTarget(path, targetIndex, context.landingMode);
        final double desiredAltitude = this.computeOverworldDesiredAltitude(path, targetIndex, target, context.landingMode);
        final Vec3 controlTarget = new Vec3(target.x, desiredAltitude, target.z);
        final Rotation rawRotation = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), controlTarget, ctx.playerRotations());
        final PitchSolveResult solvedPitch = this.solvePitch(context, controlTarget, 1);
        final float pitch = solvedPitch != null
                ? solvedPitch.result.pitch
                : this.computeOverworldPitch(rawRotation.getPitch(), desiredAltitude, context.landingMode);
        final boolean forceUseFirework = (solvedPitch != null && solvedPitch.forceUseFirework)
                || this.shouldForceOverworldFirework(desiredAltitude, controlTarget, context.boost.isBoosted(), context.landingMode);
        return new Solution(context, new Rotation(rawRotation.getYaw(), pitch), controlTarget, true, forceUseFirework);
    }

    private Solution solveOverworldLandingAngles(final SolverContext context, final NetherPath path) {
        if (this.waterLandingTarget) {
            return this.solveOverworldWaterLandingAngles(context);
        }
        final LandingProfile profile = this.buildLandingProfile(context);
        if (profile == null) {
            return null;
        }

        LandingOption bestTouchdown = null;
        for (LandingCandidate candidate : this.buildLandingTouchdownCandidates(profile)) {
            final Rotation rawRotation = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), candidate.target, ctx.playerRotations());
            final PitchSolveResult solvedPitch = this.solvePitch(context, candidate.target, 1, true);
            if (solvedPitch == null || solvedPitch.result.landing == null) {
                continue;
            }
            final LandingPrediction prediction = solvedPitch.result.landing;
            if (!prediction.safeTouchdown) {
                continue;
            }
            final LandingOption option = new LandingOption(candidate, solvedPitch, rawRotation.getYaw());
            if (bestTouchdown == null || prediction.score < bestTouchdown.solve.result.landing.score) {
                bestTouchdown = option;
            }
        }
        if (bestTouchdown != null) {
            return new Solution(context, new Rotation(bestTouchdown.yaw, bestTouchdown.solve.result.pitch), bestTouchdown.candidate.target, true, false);
        }

        Solution fallback = null;
        for (LandingCandidate candidate : this.buildLandingApproachCandidates(profile)) {
            final Rotation rawRotation = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), candidate.target, ctx.playerRotations());
            final PitchSolveResult solvedPitch = this.solvePitch(context, candidate.target, 1, false);
            if (solvedPitch != null) {
                return new Solution(context, new Rotation(rawRotation.getYaw(), solvedPitch.result.pitch), candidate.target, true, false);
            }
            if (fallback == null) {
                fallback = new Solution(context, new Rotation(rawRotation.getYaw(), rawRotation.getPitch()), candidate.target, false, false);
            }
        }
        if (fallback != null) {
            return fallback;
        }
        final Vec3 fallbackTarget = this.buildLandingControlTarget(profile, profile.approachDistance, profile.approachHeight);
        final Rotation rawRotation = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), fallbackTarget, ctx.playerRotations());
        return new Solution(context, new Rotation(rawRotation.getYaw(), rawRotation.getPitch()), fallbackTarget, false, false);
    }

    private Solution solveOverworldWaterLandingAngles(final SolverContext context) {
        final Vec3 landingCenter = this.getLandingPadCenter();
        if (landingCenter == null) {
            return null;
        }
        final Vec3 horizontalMotion = context.motion.multiply(1.0D, 0.0D, 1.0D);
        Vec3 runwayDir = horizontalMotion;
        if (runwayDir.lengthSqr() < 1.0E-6D) {
            runwayDir = new Vec3(landingCenter.x - context.start.x, 0.0D, landingCenter.z - context.start.z);
        }
        if (runwayDir.lengthSqr() < 1.0E-6D) {
            runwayDir = new Vec3(1.0D, 0.0D, 0.0D);
        } else {
            runwayDir = runwayDir.normalize();
        }

        final double horizontalDistance = Math.sqrt(this.horizontalDistanceSq(context.start, landingCenter));
        final double altitudeAbovePad = Math.max(0.0D, context.start.y - landingCenter.y);
        final double horizontalSpeed = horizontalMotion.length();
        final double downwardSpeed = Math.max(0.0D, -context.motion.y);

        final Vec3 target;
        final float pitch;
        if (horizontalDistance > 6.0D) {
            final double setupDistance = Mth.clamp(horizontalSpeed * 8.0D + altitudeAbovePad * 0.08D + 6.0D, 6.0D, 20.0D);
            final double setupHeight = Mth.clamp(8.0D + horizontalSpeed * 5.0D + downwardSpeed * 6.0D, 8.0D, 28.0D);
            target = landingCenter.subtract(runwayDir.scale(Math.min(setupDistance, Math.max(2.0D, horizontalDistance - 2.0D))))
                    .add(0.0D, Math.min(setupHeight, altitudeAbovePad + 4.0D), 0.0D);
            final Rotation rawRotation = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), target, ctx.playerRotations());
            final PitchSolveResult solvedPitch = this.solvePitch(context, target, 1, false);
            pitch = solvedPitch != null ? solvedPitch.result.pitch : rawRotation.getPitch();
        } else {
            target = landingCenter.add(0.0D, -4.0D, 0.0D);
            final Rotation rawRotation = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), target, ctx.playerRotations());
            pitch = Mth.clamp(Math.max(rawRotation.getPitch(), 65.0F), 60.0F, 85.0F);
        }

        final Rotation finalRotation = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), target, ctx.playerRotations()).withPitch(pitch);
        return new Solution(context, finalRotation, target, true, false);
    }

    private Solution solveNetherAngles(final SolverContext context) {
        final NetherPath path = context.path;
        final int playerNear = context.landingMode ? path.size() - 1 : context.playerNear;
        final Vec3 start = context.start;
        Solution solution = null;

        for (int relaxation = 0; relaxation < 3; relaxation++) { // try for a strict solution first, then relax more and more (if we're in a corner or near some blocks, it will have to relax its constraints a bit)
            int[] heights = context.boost.isBoosted() ? new int[]{20, 10, 5, 0} : new int[]{0}; // attempt to gain height, if we can, so as not to waste the boost
            int lookahead = relaxation == 0 ? 2 : 3; // ideally this would be expressed as a distance in blocks, rather than a number of voxel steps
            //int minStep = Math.max(0, playerNear - relaxation);
            int minStep = playerNear;

            for (int i = Math.min(playerNear + 20, path.size() - 1); i >= minStep; i--) {
                final List<Pair<Vec3, Integer>> candidates = new ArrayList<>();
                for (int dy : heights) {
                    if (relaxation == 0 || i == minStep) {
                        // no interp
                        candidates.add(new Pair<>(path.getVec(i), dy));
                    } else if (relaxation == 1) {
                        final double[] interps = new double[]{1.0, 0.75, 0.5, 0.25};
                        for (double interp : interps) {
                            final Vec3 dest = interp == 1.0
                                    ? path.getVec(i)
                                    : path.getVec(i).scale(interp).add(path.getVec(i - 1).scale(1.0 - interp));
                            candidates.add(new Pair<>(dest, dy));
                        }
                    } else {
                        // Create a point along the segment every block
                        final Vec3 delta = path.getVec(i).subtract(path.getVec(i - 1));
                        final int steps = fastFloor(delta.length());
                        final Vec3 step = delta.normalize();
                        Vec3 stepped = path.getVec(i);
                        for (int interp = 0; interp < steps; interp++) {
                            candidates.add(new Pair<>(stepped, dy));
                            stepped = stepped.subtract(step);
                        }
                    }
                }

                for (final Pair<Vec3, Integer> candidate : candidates) {
                    final Integer augment = candidate.second();
                    Vec3 dest = candidate.first().add(0, augment, 0);
                    if (context.landingMode) {
                        dest = dest.add(0.5, 0.5, 0.5);
                    }

                    if (augment != 0) {
                        if (i + lookahead >= path.size()) {
                            continue;
                        }
                        if (start.distanceTo(dest) < 40) {
                            if (!this.clearView(dest, path.getVec(i + lookahead).add(0, augment, 0), false)
                                    || !this.clearView(dest, path.getVec(i + lookahead), false)) {
                                // aka: don't go upwards if doing so would prevent us from being able to see the next position **OR** the modified next position
                                continue;
                            }
                        } else {
                            // but if it's far away, allow gaining altitude if we could lose it again by the time we get there
                            if (!this.clearView(dest, path.getVec(i), false)) {
                                continue;
                            }
                        }
                    }

                    final double minAvoidance = Baritone.settings().elytraMinimumAvoidance.value;
                    final Double growth = relaxation == 2 ? null
                            : relaxation == 0 ? 2 * minAvoidance : minAvoidance;

                    if (this.isHitboxClear(context, dest, growth)) {
                        // Yaw is trivial, just calculate the rotation required to face the destination
                        final float yaw = RotationUtils.calcRotationFromVec3d(start, dest, ctx.playerRotations()).getYaw();

                        final PitchSolveResult pitch = this.solvePitch(context, dest, relaxation);
                        if (pitch == null) {
                            solution = new Solution(context, new Rotation(yaw, ctx.playerRotations().getPitch()), null, false, false);
                            continue;
                        }

                        // A solution was found with yaw AND pitch, so just immediately return it.
                        return new Solution(context, new Rotation(yaw, pitch.result.pitch), dest, true, pitch.forceUseFirework);
                    }
                }
            }
        }
        return solution;
    }

    private void tickUseFireworks(final Vec3 start, final Vec3 goingTo, final boolean isBoosted, final boolean forceUseFirework) {
        if (this.remainingSetBackTicks > 0) {
            logDebug("waiting for elytraFireworkSetbackUseDelay: " + this.remainingSetBackTicks);
            return;
        }
        if (this.landingMode) {
            return;
        }
        final boolean useOnDescend = !Baritone.settings().elytraConserveFireworks.value || ctx.player().position().y < goingTo.y + 5;
        final double currentSpeed = new Vec3(
                ctx.player().getDeltaMovement().x,
                // ignore y component if we are BOTH below where we want to be AND descending
                ctx.player().position().y < goingTo.y ? Math.max(0, ctx.player().getDeltaMovement().y) : ctx.player().getDeltaMovement().y,
                ctx.player().getDeltaMovement().z
        ).lengthSqr();

        final double elytraFireworkSpeed = Baritone.settings().elytraFireworkSpeed.value;
        if (this.remainingFireworkTicks <= 0 && (forceUseFirework || (!isBoosted
                && useOnDescend
                && (ctx.player().position().y < goingTo.y - 5 || start.distanceTo(new Vec3(goingTo.x + 0.5, ctx.player().position().y, goingTo.z + 0.5)) > 5) // UGH!!!!!!!
                && currentSpeed < elytraFireworkSpeed * elytraFireworkSpeed))
        ) {
            // Prioritize boosting fireworks over regular ones
            // TODO: Take the minimum boost time into account?
            if (!baritone.getInventoryBehavior().throwaway(true, ElytraBehavior::isBoostingFireworks) &&
                    !baritone.getInventoryBehavior().throwaway(true, ElytraBehavior::isFireworks)) {
                logDirect("no fireworks");
                return;
            }
            logVerbose("attempting to use firework" + (forceUseFirework ? " (forced)" : ""));
            ctx.playerController().processRightClick(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND);
            this.minimumBoostTicks = 10 * (1 + getFireworkBoost(ctx.player().getItemInHand(InteractionHand.MAIN_HAND)).orElse(0));
            this.remainingFireworkTicks = 10;
            this.deployedFireworkLastTick = true;
        }
    }

    private final class SolverContext {

        public final NetherPath path;
        public final int playerNear;
        public final Vec3 start;
        public final Vec3 motion;
        public final AABB boundingBox;
        public final boolean ignoreLava;
        public final FireworkBoost boost;
        public final IAimProcessor aimProcessor;
        public final boolean landingMode;

        /**
         * Creates a new SolverContext using the current state of the path, player, and firework boost at the time of
         * construction.
         *
         * @param async Whether the computation is being done asynchronously at the end of a game tick.
         */
        public SolverContext(boolean async) {
            this.path = ElytraBehavior.this.pathManager.getPath();
            this.playerNear = ElytraBehavior.this.pathManager.getNear();

            this.start = ctx.playerFeetAsVec();
            this.motion = ctx.playerMotion();
            this.boundingBox = ctx.player().getBoundingBox();
            this.ignoreLava = ctx.player().isInLava();
            this.landingMode = ElytraBehavior.this.landingMode;

            final Integer fireworkTicksExisted;
            if (async && ElytraBehavior.this.deployedFireworkLastTick) {
                final int[] counter = ElytraBehavior.this.nextTickBoostCounter;
                fireworkTicksExisted = counter[1] > counter[0] ? 0 : null;
            } else {
                fireworkTicksExisted = ElytraBehavior.this.getAttachedFirework().map(e -> e.tickCount).orElse(null);
            }
            this.boost = new FireworkBoost(fireworkTicksExisted, ElytraBehavior.this.minimumBoostTicks);

            ITickableAimProcessor aim = ElytraBehavior.this.baritone.getLookBehavior().getAimProcessor().fork();
            if (async) {
                // async computation is done at the end of a tick, advance by 1 to prepare for the next tick
                aim.advance(1);
            }
            this.aimProcessor = aim;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || o.getClass() != SolverContext.class) {
                return false;
            }

            SolverContext other = (SolverContext) o;
            return this.path == other.path  // Contents aren't modified, just compare by reference
                    && this.playerNear == other.playerNear
                    && Objects.equals(this.start, other.start)
                    && Objects.equals(this.motion, other.motion)
                    && Objects.equals(this.boundingBox, other.boundingBox)
                    && this.ignoreLava == other.ignoreLava
                    && this.landingMode == other.landingMode
                    && Objects.equals(this.boost, other.boost);
        }
    }

    private static final class FireworkBoost {

        private final Integer fireworkTicksExisted;
        private final int minimumBoostTicks;
        private final int maximumBoostTicks;

        /**
         * @param fireworkTicksExisted The ticksExisted of the attached firework entity, or {@code null} if no entity.
         * @param minimumBoostTicks    The minimum number of boost ticks that the attached firework entity, if any, will
         *                             provide.
         */
        public FireworkBoost(final Integer fireworkTicksExisted, final int minimumBoostTicks) {
            this.fireworkTicksExisted = fireworkTicksExisted;

            // this.lifetime = 10 * i + this.rand.nextInt(6) + this.rand.nextInt(7);
            this.minimumBoostTicks = minimumBoostTicks;
            this.maximumBoostTicks = minimumBoostTicks + 11;
        }

        public boolean isBoosted() {
            return this.fireworkTicksExisted != null;
        }

        /**
         * @return The guaranteed number of remaining ticks with boost
         */
        public int getGuaranteedBoostTicks() {
            return this.isBoosted() ? Math.max(0, this.minimumBoostTicks - this.fireworkTicksExisted) : 0;
        }

        /**
         * @return The maximum number of remaining ticks with boost
         */
        public int getMaximumBoostTicks() {
            return this.isBoosted() ? Math.max(0, this.maximumBoostTicks - this.fireworkTicksExisted) : 0;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || o.getClass() != FireworkBoost.class) {
                return false;
            }

            FireworkBoost other = (FireworkBoost) o;
            if (!this.isBoosted() && !other.isBoosted()) {
                return true;
            }

            return Objects.equals(this.fireworkTicksExisted, other.fireworkTicksExisted)
                    && this.minimumBoostTicks == other.minimumBoostTicks
                    && this.maximumBoostTicks == other.maximumBoostTicks;
        }
    }

    private static final class PitchResult {

        public final float pitch;
        public final double dot;
        public final List<Vec3> steps;
        public final LandingPrediction landing;

        public PitchResult(float pitch, double dot, List<Vec3> steps, LandingPrediction landing) {
            this.pitch = pitch;
            this.dot = dot;
            this.steps = steps;
            this.landing = landing;
        }
    }

    private static final class PitchSolveResult {

        public final PitchResult result;
        public final boolean forceUseFirework;

        public PitchSolveResult(PitchResult result, boolean forceUseFirework) {
            this.result = result;
            this.forceUseFirework = forceUseFirework;
        }
    }

    private static final class LandingProfile {

        public final Vec3 landingCenter;
        public final Vec3 runwayDir;
        public final double horizontalSpeed;
        public final double downwardSpeed;
        public final double altitudeAbovePad;
        public final double currentDistance;
        public final double prepDistance;
        public final double approachDistance;
        public final double commitDistance;
        public final double approachHeight;
        public final double flareHeight;

        public LandingProfile(Vec3 landingCenter, Vec3 runwayDir, double horizontalSpeed, double downwardSpeed, double altitudeAbovePad, double currentDistance, double prepDistance, double approachDistance, double commitDistance, double approachHeight, double flareHeight) {
            this.landingCenter = landingCenter;
            this.runwayDir = runwayDir;
            this.horizontalSpeed = horizontalSpeed;
            this.downwardSpeed = downwardSpeed;
            this.altitudeAbovePad = altitudeAbovePad;
            this.currentDistance = currentDistance;
            this.prepDistance = prepDistance;
            this.approachDistance = approachDistance;
            this.commitDistance = commitDistance;
            this.approachHeight = approachHeight;
            this.flareHeight = flareHeight;
        }
    }

    private static final class LandingCandidate {

        public final Vec3 target;
        public final double shortDistance;
        public final double height;

        public LandingCandidate(Vec3 target, double shortDistance, double height) {
            this.target = target;
            this.shortDistance = shortDistance;
            this.height = height;
        }
    }

    private static final class LandingOption {

        public final LandingCandidate candidate;
        public final PitchSolveResult solve;
        public final float yaw;

        public LandingOption(LandingCandidate candidate, PitchSolveResult solve, float yaw) {
            this.candidate = candidate;
            this.solve = solve;
            this.yaw = yaw;
        }
    }

    private static final class LandingPrediction {

        public final Vec3 touchdown;
        public final Vec3 impactMotion;
        public final double alongError;
        public final double crossError;
        public final double projectedStopError;
        public final double downwardSpeed;
        public final double horizontalSpeed;
        public final boolean safeTouchdown;
        public final double score;

        public LandingPrediction(Vec3 touchdown, Vec3 impactMotion, double alongError, double crossError, double projectedStopError, double downwardSpeed, double horizontalSpeed, boolean safeTouchdown, double score) {
            this.touchdown = touchdown;
            this.impactMotion = impactMotion;
            this.alongError = alongError;
            this.crossError = crossError;
            this.projectedStopError = projectedStopError;
            this.downwardSpeed = downwardSpeed;
            this.horizontalSpeed = horizontalSpeed;
            this.safeTouchdown = safeTouchdown;
            this.score = score;
        }
    }

    private static final class SimulationResult {

        public final List<Vec3> steps;
        public final Vec3 finalMotion;
        public final LandingPrediction landing;

        public SimulationResult(List<Vec3> steps, Vec3 finalMotion, LandingPrediction landing) {
            this.steps = steps;
            this.finalMotion = finalMotion;
            this.landing = landing;
        }
    }

    private static final class Solution {

        public final SolverContext context;
        public final Rotation rotation;
        public final Vec3 goingTo;
        public final boolean solvedPitch;
        public final boolean forceUseFirework;

        public Solution(SolverContext context, Rotation rotation, Vec3 goingTo, boolean solvedPitch, boolean forceUseFirework) {
            this.context = context;
            this.rotation = rotation;
            this.goingTo = goingTo;
            this.solvedPitch = solvedPitch;
            this.forceUseFirework = forceUseFirework;
        }
    }

    public static boolean isFireworks(final ItemStack itemStack) {
        if (itemStack.getItem() != Items.FIREWORK_ROCKET) {
            return false;
        }
        Fireworks fw = itemStack.get(DataComponents.FIREWORKS);
        return fw != null && fw.explosions().isEmpty();
    }

    private static boolean isBoostingFireworks(final ItemStack itemStack) {
        return getFireworkBoost(itemStack).isPresent();
    }

    private static OptionalInt getFireworkBoost(final ItemStack itemStack) {
        Fireworks fw = itemStack.get(DataComponents.FIREWORKS);
        if (fw != null && fw.explosions().isEmpty()) {
            return OptionalInt.of(fw.flightDuration());
        }
        return OptionalInt.empty();
    }

    private Optional<FireworkRocketEntity> getAttachedFirework() {
        return ctx.entitiesStream()
                .filter(x -> x instanceof FireworkRocketEntity)
                .filter(x -> Objects.equals(((IFireworkRocketEntity) x).getBoostedEntity(), ctx.player()))
                .map(x -> (FireworkRocketEntity) x)
                .findFirst();
    }

    private boolean isHitboxClear(final SolverContext context, final Vec3 dest, final Double growAmount) {
        final Vec3 start = context.start;
        final boolean ignoreLava = context.ignoreLava;

        if (!this.clearView(start, dest, ignoreLava)) {
            return false;
        }
        if (growAmount == null) {
            return true;
        }

        final AABB bb = context.boundingBox.inflate(growAmount);

        final double ox = dest.x - start.x;
        final double oy = dest.y - start.y;
        final double oz = dest.z - start.z;

        final double[] src = new double[]{
                bb.minX, bb.minY, bb.minZ,
                bb.minX, bb.minY, bb.maxZ,
                bb.minX, bb.maxY, bb.minZ,
                bb.minX, bb.maxY, bb.maxZ,
                bb.maxX, bb.minY, bb.minZ,
                bb.maxX, bb.minY, bb.maxZ,
                bb.maxX, bb.maxY, bb.minZ,
                bb.maxX, bb.maxY, bb.maxZ,
        };
        final double[] dst = new double[]{
                bb.minX + ox, bb.minY + oy, bb.minZ + oz,
                bb.minX + ox, bb.minY + oy, bb.maxZ + oz,
                bb.minX + ox, bb.maxY + oy, bb.minZ + oz,
                bb.minX + ox, bb.maxY + oy, bb.maxZ + oz,
                bb.maxX + ox, bb.minY + oy, bb.minZ + oz,
                bb.maxX + ox, bb.minY + oy, bb.maxZ + oz,
                bb.maxX + ox, bb.maxY + oy, bb.minZ + oz,
                bb.maxX + ox, bb.maxY + oy, bb.maxZ + oz,
        };

        // Use non-batching method without early failure
        if (Baritone.settings().elytraRenderHitboxRaytraces.value) {
            boolean clear = true;
            for (int i = 0; i < 8; i++) {
                final Vec3 s = new Vec3(src[i * 3], src[i * 3 + 1], src[i * 3 + 2]);
                final Vec3 d = new Vec3(dst[i * 3], dst[i * 3 + 1], dst[i * 3 + 2]);
                // Don't forward ignoreLava since the batch call doesn't care about it
                if (!this.clearView(s, d, false)) {
                    clear = false;
                }
            }
            return clear;
        }

        if (this.context != null && !ignoreLava) {
            return this.context.raytrace(8, src, dst, NetherPathfinderContext.Visibility.ALL);
        }

        for (int i = 0; i < 8; i++) {
            final Vec3 s = new Vec3(src[i * 3], src[i * 3 + 1], src[i * 3 + 2]);
            final Vec3 d = new Vec3(dst[i * 3], dst[i * 3 + 1], dst[i * 3 + 2]);
            if (!this.clearView(s, d, ignoreLava)) {
                return false;
            }
        }
        return true;
    }

    public boolean clearView(Vec3 start, Vec3 dest, boolean ignoreLava) {
        final boolean clear;
        if (!ignoreLava && this.context != null) {
            // if start == dest then the cpp raytracer dies
            clear = start.equals(dest) || this.context.raytrace(start, dest);
        } else {
            clear = this.voxelRaytraceClear(start, dest, ignoreLava);
        }

        if (Baritone.settings().elytraRenderRaytraces.value) {
            (clear ? this.clearLines : this.blockedLines).add(new Pair<>(start, dest));
        }
        return clear;
    }

    private static FloatArrayList pitchesToSolveFor(final float goodPitch, final boolean desperate) {
        final float minPitch = desperate ? -90 : Math.max(goodPitch - Baritone.settings().elytraPitchRange.value, -89);
        final float maxPitch = desperate ? 90 : Math.min(goodPitch + Baritone.settings().elytraPitchRange.value, 89);

        final FloatArrayList pitchValues = new FloatArrayList(fastCeil(maxPitch - minPitch) + 1);
        for (float pitch = goodPitch; pitch <= maxPitch; pitch++) {
            pitchValues.add(pitch);
        }
        for (float pitch = goodPitch - 1; pitch >= minPitch; pitch--) {
            pitchValues.add(pitch);
        }

        return pitchValues;
    }

    private static FloatArrayList pitchesToSolveForLanding(final float goodPitch) {
        final float minPitch = Math.max(goodPitch - 10.0F, -80.0F);
        final float maxPitch = Math.min(goodPitch + 10.0F, 50.0F);
        final FloatArrayList pitchValues = new FloatArrayList(11);
        pitchValues.add(goodPitch);
        for (float delta = 2.0F; delta <= 10.0F; delta += 2.0F) {
            if (goodPitch + delta <= maxPitch) {
                pitchValues.add(goodPitch + delta);
            }
            if (goodPitch - delta >= minPitch) {
                pitchValues.add(goodPitch - delta);
            }
        }
        return pitchValues;
    }

    @FunctionalInterface
    private interface IntTriFunction<T> {
        T apply(int first, int second, int third);
    }

    private static final class IntTriple {
        public final int first;
        public final int second;
        public final int third;

        public IntTriple(int first, int second, int third) {
            this.first = first;
            this.second = second;
            this.third = third;
        }
    }

    private PitchSolveResult solvePitch(final SolverContext context, final Vec3 goal, final int relaxation) {
        return this.solvePitch(context, goal, relaxation, context.landingMode);
    }

    private PitchSolveResult solvePitch(final SolverContext context, final Vec3 goal, final int relaxation, final boolean requireTouchdown) {
        final boolean desperate = relaxation == 2;
        final float goodPitch = RotationUtils.calcRotationFromVec3d(context.start, goal, ctx.playerRotations()).getPitch();
        final FloatArrayList pitches = context.landingMode
                ? pitchesToSolveForLanding(goodPitch)
                : pitchesToSolveFor(goodPitch, desperate);

        final IntTriFunction<PitchResult> solve = (ticks, ticksBoosted, ticksBoostDelay) ->
                this.solvePitch(context, goal, relaxation, pitches.iterator(), ticks, ticksBoosted, ticksBoostDelay, requireTouchdown);

        final List<IntTriple> tests = new ArrayList<>();

        if (context.boost.isBoosted()) {
            final int guaranteed = context.boost.getGuaranteedBoostTicks();
            if (guaranteed == 0) {
                // uncertain when boost will run out
                final int lookahead = Math.max(4, 10 - context.boost.getMaximumBoostTicks());
                tests.add(new IntTriple(lookahead, 1, 0));
            } else if (guaranteed <= 5) {
                // boost will run out within 5 ticks
                tests.add(new IntTriple(guaranteed + 5, guaranteed, 0));
            } else {
                // there's plenty of guaranteed boost
                tests.add(new IntTriple(guaranteed + 1, guaranteed, 0));
            }
        }

        // Standard test, assume (not) boosted for entire duration
        final double horizontalDistance = Math.sqrt(this.horizontalDistanceSq(context.start, goal));
        final double horizontalSpeed = Math.max(0.35D, context.motion.multiply(1.0D, 0.0D, 1.0D).length());
        final int landingTicks = Mth.clamp(fastCeil(horizontalDistance / horizontalSpeed) + (requireTouchdown ? 10 : 6), requireTouchdown ? 24 : 14, requireTouchdown ? 72 : 40);
        final int ticks = context.landingMode
                ? landingTicks
                : desperate ? 3 : context.boost.isBoosted() ? Math.max(5, context.boost.getGuaranteedBoostTicks()) : Baritone.settings().elytraSimulationTicks.value;
        tests.add(new IntTriple(ticks, context.boost.isBoosted() ? ticks : 0, 0));

        final Optional<PitchResult> result = tests.stream()
                .map(i -> solve.apply(i.first, i.second, i.third))
                .filter(Objects::nonNull)
                .findFirst();
        if (result.isPresent()) {
            return new PitchSolveResult(result.get(), false);
        }

        // If we used a firework would we be able to get out of the current situation??? perhaps
        if (desperate && !context.landingMode) {
            final List<IntTriple> testsBoost = new ArrayList<>();
            testsBoost.add(new IntTriple(ticks, 10, 3));
            testsBoost.add(new IntTriple(ticks, 10, 2));
            testsBoost.add(new IntTriple(ticks, 10, 1));

            final Optional<PitchResult> resultBoost = testsBoost.stream()
                    .map(i -> solve.apply(i.first, i.second, i.third))
                    .filter(Objects::nonNull)
                    .findFirst();
            if (resultBoost.isPresent()) {
                return new PitchSolveResult(resultBoost.get(), true);
            }
        }

        return null;
    }

    private PitchResult solvePitch(final SolverContext context, final Vec3 goal, final int relaxation,
                                   final FloatIterator pitches, final int ticks, final int ticksBoosted,
                                   final int ticksBoostDelay, final boolean requireTouchdown) {
        // we are at a certain velocity, but we have a target velocity
        // what pitch would get us closest to our target velocity?
        // yaw is easy so we only care about pitch

        final Vec3 goalDelta = goal.subtract(context.start);
        final Vec3 goalDirection = goalDelta.normalize();

        final Deque<PitchResult> bestResults = new ArrayDeque<>();

        while (pitches.hasNext()) {
            final float pitch = pitches.nextFloat();
            final SimulationResult simulation = this.simulate(
                    context,
                    goalDelta,
                    pitch,
                    ticks,
                    ticksBoosted,
                    ticksBoostDelay
            );
            if (simulation == null) {
                continue;
            }
            final List<Vec3> displacement = simulation.steps;
            final Vec3 last = displacement.get(displacement.size() - 1);
            double goodness = goalDirection.dot(last.normalize());
            LandingPrediction landingPrediction = null;
            if (requireTouchdown) {
                landingPrediction = simulation.landing;
                if (landingPrediction == null) {
                    continue;
                }
                goodness = -landingPrediction.score;
            }
            final PitchResult bestSoFar = bestResults.peek();
            if (bestSoFar == null || goodness > bestSoFar.dot) {
                bestResults.push(new PitchResult(pitch, goodness, displacement, landingPrediction));
            }
        }

        outer:
        for (final PitchResult result : bestResults) {
            if (requireTouchdown) {
                this.simulationLine = result.steps;
                return result;
            }
            if (relaxation < 2) {
                // Ensure that the goal is visible along the entire simulated path
                // Reverse order iteration since the last position is most likely to fail
                for (int i = result.steps.size() - 1; i >= 1; i--) {
                    if (!clearView(context.start.add(result.steps.get(i)), goal, context.ignoreLava)) {
                        continue outer;
                    }
                }
            } else {
                // Ensure that the goal is visible from the final position
                if (!clearView(context.start.add(result.steps.get(result.steps.size() - 1)), goal, context.ignoreLava)) {
                    continue;
                }
            }

            this.simulationLine = result.steps;
            return result;
        }
        return null;
    }

    private SimulationResult simulate(final SolverContext context, final Vec3 goalDelta, final float pitch, final int ticks,
                                      final int ticksBoosted, final int ticksBoostDelay) {
        final ITickableAimProcessor aimProcessor = context.aimProcessor.fork();
        Vec3 delta = goalDelta;
        Vec3 motion = context.motion;
        AABB hitbox = context.boundingBox;
        List<Vec3> displacement = new ArrayList<>(ticks + 1);
        displacement.add(Vec3.ZERO);
        int remainingTicksBoosted = ticksBoosted;
        final double touchdownY = this.landingTarget != null ? this.landingTarget.y + 1.0D : Double.NEGATIVE_INFINITY;

        for (int i = 0; i < ticks; i++) {
            final double cx = hitbox.minX + (hitbox.maxX - hitbox.minX) * 0.5D;
            final double cz = hitbox.minZ + (hitbox.maxZ - hitbox.minZ) * 0.5D;
            if (delta.lengthSqr() < 1) {
                break;
            }
            final Rotation rotation = aimProcessor.nextRotation(
                    RotationUtils.calcRotationFromVec3d(Vec3.ZERO, delta, ctx.playerRotations()).withPitch(pitch)
            );
            final Vec3 lookDirection = RotationUtils.calcLookDirectionFromRotation(rotation);

            motion = step(motion, lookDirection, rotation.getPitch());
            delta = delta.subtract(motion);
            final Vec3 currentOffset = displacement.get(displacement.size() - 1);
            final AABB nextHitbox = hitbox.move(motion);
            final boolean landingTouchdown = context.landingMode && this.landingTarget != null
                    && hitbox.minY > touchdownY
                    && nextHitbox.minY <= touchdownY;

            // Collision box while the player is in motion, with additional padding for safety
            final AABB inMotion = hitbox.inflate(motion.x, motion.y, motion.z).inflate(0.01);

            int xmin = fastFloor(inMotion.minX);
            int xmax = fastCeil(inMotion.maxX);
            int ymin = fastFloor(inMotion.minY);
            int ymax = fastCeil(inMotion.maxY);
            int zmin = fastFloor(inMotion.minZ);
            int zmax = fastCeil(inMotion.maxZ);
            for (int x = xmin; x < xmax; x++) {
                for (int y = ymin; y < ymax; y++) {
                    for (int z = zmin; z < zmax; z++) {
                        if (!this.passable(x, y, z, context.ignoreLava)) {
                            if (landingTouchdown && y + 1.0D <= touchdownY + 1.0E-6D) {
                                continue;
                            }
                            return null;
                        }
                    }
                }
            }

            if (landingTouchdown) {
                final double denominator = hitbox.minY - nextHitbox.minY;
                final double interpolation = denominator <= 1.0E-6D
                        ? 1.0D
                        : Mth.clamp((hitbox.minY - touchdownY) / denominator, 0.0D, 1.0D);
                final Vec3 touchdownOffset = currentOffset.add(motion.scale(interpolation));
                displacement.add(touchdownOffset);
                return new SimulationResult(displacement, motion, this.createLandingPrediction(context, touchdownOffset, motion));
            }

            hitbox = nextHitbox;
            displacement.add(currentOffset.add(motion));

            if (i >= ticksBoostDelay && remainingTicksBoosted-- > 0) {
                // See EntityFireworkRocket
                motion = motion.add(
                        lookDirection.x * 0.1 + (lookDirection.x * 1.5 - motion.x) * 0.5,
                        lookDirection.y * 0.1 + (lookDirection.y * 1.5 - motion.y) * 0.5,
                        lookDirection.z * 0.1 + (lookDirection.z * 1.5 - motion.z) * 0.5
                );
            }
        }

        return new SimulationResult(displacement, motion, null);
    }

    private LandingPrediction createLandingPrediction(final SolverContext context, final Vec3 touchdownOffset, final Vec3 impactMotion) {
        if (this.landingTarget == null) {
            return null;
        }
        final Vec3 landingCenter = new Vec3(this.landingTarget.x + 0.5D, this.landingTarget.y + 1.0D, this.landingTarget.z + 0.5D);
        final Vec3 touchdown = context.start.add(touchdownOffset);
        Vec3 runwayDir = context.motion.multiply(1.0D, 0.0D, 1.0D);
        if (runwayDir.lengthSqr() < 1.0E-6D) {
            runwayDir = new Vec3(landingCenter.x - context.start.x, 0.0D, landingCenter.z - context.start.z);
        }
        if (runwayDir.lengthSqr() < 1.0E-6D) {
            runwayDir = new Vec3(1.0D, 0.0D, 0.0D);
        } else {
            runwayDir = runwayDir.normalize();
        }
        final Vec3 lateralDir = new Vec3(-runwayDir.z, 0.0D, runwayDir.x);
        final Vec3 touchdownDelta = new Vec3(touchdown.x - landingCenter.x, 0.0D, touchdown.z - landingCenter.z);
        final double alongError = touchdownDelta.dot(runwayDir);
        final double crossError = touchdownDelta.dot(lateralDir);
        final double downwardSpeed = Math.max(0.0D, -impactMotion.y);
        final double horizontalSpeed = impactMotion.multiply(1.0D, 0.0D, 1.0D).length();
        final double projectedCarry = Math.max(0.0D, horizontalSpeed - 0.65D) * 4.0D;
        final double projectedStopError = alongError + projectedCarry;
        final double verticalPenalty = Math.max(0.0D, downwardSpeed - 0.42D);
        final double stopPenalty = Math.max(0.0D, Math.abs(projectedStopError) - 1.5D);
        final double crossPenalty = Math.max(0.0D, Math.abs(crossError) - 1.25D);
        final boolean safeTouchdown = downwardSpeed <= 0.42D
                && horizontalSpeed <= 1.8D
                && Math.abs(projectedStopError) <= 3.0D
                && Math.abs(crossError) <= 2.0D;
        double score = verticalPenalty * verticalPenalty * 1800.0D
                + stopPenalty * stopPenalty * 80.0D
                + crossPenalty * crossPenalty * 120.0D
                + alongError * alongError * 6.0D
                + crossError * crossError * 14.0D;
        if (!safeTouchdown) {
            score += 5000.0D;
        }
        return new LandingPrediction(touchdown, impactMotion, alongError, crossError, projectedStopError, downwardSpeed, horizontalSpeed, safeTouchdown, score);
    }

    private static Vec3 step(final Vec3 motion, final Vec3 lookDirection, final float pitch) {
        double motionX = motion.x;
        double motionY = motion.y;
        double motionZ = motion.z;

        float pitchRadians = pitch * RotationUtils.DEG_TO_RAD_F;
        double pitchBase2 = Math.sqrt(lookDirection.x * lookDirection.x + lookDirection.z * lookDirection.z);
        double flatMotion = Math.sqrt(motionX * motionX + motionZ * motionZ);
        double thisIsAlwaysOne = lookDirection.length();
        float pitchBase3 = Mth.cos(pitchRadians);
        //System.out.println("always the same lol " + -pitchBase + " " + pitchBase3);
        //System.out.println("always the same lol " + Math.abs(pitchBase3) + " " + pitchBase2);
        //System.out.println("always 1 lol " + thisIsAlwaysOne);
        pitchBase3 = (float) ((double) pitchBase3 * (double) pitchBase3 * Math.min(1, thisIsAlwaysOne / 0.4));
        motionY += -0.08 + (double) pitchBase3 * 0.06;
        if (motionY < 0 && pitchBase2 > 0) {
            double speedModifier = motionY * -0.1 * (double) pitchBase3;
            motionY += speedModifier;
            motionX += lookDirection.x * speedModifier / pitchBase2;
            motionZ += lookDirection.z * speedModifier / pitchBase2;
        }
        if (pitchRadians < 0) { // if you are looking down (below level)
            double anotherSpeedModifier = flatMotion * (double) (-Mth.sin(pitchRadians)) * 0.04;
            motionY += anotherSpeedModifier * 3.2;
            motionX -= lookDirection.x * anotherSpeedModifier / pitchBase2;
            motionZ -= lookDirection.z * anotherSpeedModifier / pitchBase2;
        }
        if (pitchBase2 > 0) { // this is always true unless you are looking literally straight up (let's just say the bot will never do that)
            motionX += (lookDirection.x / pitchBase2 * flatMotion - motionX) * 0.1;
            motionZ += (lookDirection.z / pitchBase2 * flatMotion - motionZ) * 0.1;
        }
        motionX *= 0.99f;
        motionY *= 0.98f;
        motionZ *= 0.99f;
        //System.out.println(motionX + " " + motionY + " " + motionZ);

        return new Vec3(motionX, motionY, motionZ);
    }

    private boolean passable(int x, int y, int z, boolean ignoreLava) {
        final BlockState state = this.bsi.get0(x, y, z);
        if (ignoreLava) {
            return state.getBlock() instanceof AirBlock || MovementHelper.isLava(state);
        }
        if (this.boi != null) {
            return !this.boi.get0(x, y, z);
        }
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        return state.getCollisionShape(ctx.world(), new BlockPos(x, y, z)).isEmpty();
    }

    private boolean voxelRaytraceClear(final Vec3 start, final Vec3 end, final boolean ignoreLava) {
        if (start.equals(end)) {
            return true;
        }

        int x = fastFloor(start.x);
        int y = fastFloor(start.y);
        int z = fastFloor(start.z);
        final int endX = fastFloor(end.x);
        final int endY = fastFloor(end.y);
        final int endZ = fastFloor(end.z);

        if (!this.passable(x, y, z, ignoreLava)) {
            return false;
        }

        final double dx = end.x - start.x;
        final double dy = end.y - start.y;
        final double dz = end.z - start.z;

        final int stepX = Integer.compare((int) Math.signum(dx), 0);
        final int stepY = Integer.compare((int) Math.signum(dy), 0);
        final int stepZ = Integer.compare((int) Math.signum(dz), 0);

        double tMaxX = this.intBound(start.x, dx);
        double tMaxY = this.intBound(start.y, dy);
        double tMaxZ = this.intBound(start.z, dz);

        final double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dx);
        final double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dy);
        final double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dz);

        while (x != endX || y != endY || z != endZ) {
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
            if (!this.passable(x, y, z, ignoreLava)) {
                return false;
            }
        }
        return true;
    }

    private double intBound(final double value, final double step) {
        if (step == 0.0D) {
            return Double.POSITIVE_INFINITY;
        }
        final double fraction = value - Math.floor(value);
        if (step > 0.0D) {
            return (1.0D - fraction) / step;
        }
        return fraction / -step;
    }

    private void tickInventoryTransactions() {
        if (invTickCountdown <= 0) {
            Runnable r = invTransactionQueue.poll();
            if (r != null) {
                r.run();
                invTickCountdown = Baritone.settings().ticksBetweenInventoryMoves.value;
            }
        }
        if (invTickCountdown > 0) invTickCountdown--;
    }

    private void queueWindowClick(int windowId, int slotId, int button, ContainerInput type) {
        invTransactionQueue.add(() -> ctx.playerController().windowClick(windowId, slotId, button, type, ctx.player()));
    }

    private int findGoodElytra() {
        NonNullList<ItemStack> invy = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < invy.size(); i++) {
            ItemStack slot = invy.get(i);
            if (slot.getItem() == Items.ELYTRA && (slot.getMaxDamage() - slot.getDamageValue()) > Baritone.settings().elytraMinimumDurability.value) {
                return i;
            }
        }
        return -1;
    }

    private void trySwapElytra() {
        if (!Baritone.settings().elytraAutoSwap.value || !invTransactionQueue.isEmpty()) {
            return;
        }

        ItemStack chest = ctx.player().getItemBySlot(EquipmentSlot.CHEST);
        if (chest.getItem() != Items.ELYTRA
                || chest.getMaxDamage() - chest.getDamageValue() > Baritone.settings().elytraMinimumDurability.value) {
            return;
        }

        int goodElytraSlot = findGoodElytra();
        if (goodElytraSlot != -1) {
            final int CHEST_SLOT = 6;
            final int slotId = goodElytraSlot < 9 ? goodElytraSlot + 36 : goodElytraSlot;
            queueWindowClick(ctx.player().inventoryMenu.containerId, slotId, 0, ContainerInput.PICKUP);
            queueWindowClick(ctx.player().inventoryMenu.containerId, CHEST_SLOT, 0, ContainerInput.PICKUP);
            queueWindowClick(ctx.player().inventoryMenu.containerId, slotId, 0, ContainerInput.PICKUP);
        }
    }

    void logVerbose(String message) {
        if (Baritone.settings().elytraChatSpam.value) {
            logDebug(message);
        }
    }
}
