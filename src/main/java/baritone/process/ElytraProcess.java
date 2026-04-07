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
import baritone.api.IBaritone;
import baritone.api.event.events.*;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.IElytraProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.movements.MovementFall;
import baritone.process.elytra.ElytraBehavior;
import baritone.process.elytra.NetherPathfinderContext;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.PathingCommandContext;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.*;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

public class ElytraProcess extends BaritoneProcessHelper implements IBaritoneProcess, IElytraProcess, AbstractGameEventListener {
    private static void debug(String message) {
        System.out.println("[Baritone Elytra DBG] " + message);
    }

    public State state;
    private final boolean nativeLoaded;
    private boolean goingToLandingSpot;
    private BetterBlockPos landingSpot;
    private BlockPos missionDestination;
    private Goal missionGoal;
    private boolean arrivalAfterLanding;
    private boolean reachedGoal; // this basically just prevents potential notification spam
    private Goal goal;
    private ElytraBehavior behavior;
    private boolean predictingTerrain;
    private int configuredOverworldCruiseY;
    private int configuredOverworldWaypointDistance;
    private int configuredOverworldTerrainClearance;
    private boolean configuredOverworldWaterDive;
    private int landingRetryCooldownTicks;

    private static final int LANDING_RETRY_COOLDOWN_TICKS = 20;
    private static final double LANDING_FAILURE_DROP_BLOCKS = 4.0D;
    private static final double LANDING_TOUCHDOWN_RADIUS_SQ = 12.0D * 12.0D;

    @Override
    public void onLostControl() {
        clearRuntimeState(true);
    }

    private void clearRuntimeState(boolean clearMission) {
        this.state = State.START_FLYING; // TODO: null state?
        this.goingToLandingSpot = false;
        this.landingSpot = null;
        this.arrivalAfterLanding = false;
        this.reachedGoal = false;
        this.goal = null;
        if (clearMission) {
            this.missionDestination = null;
            this.missionGoal = null;
            this.badLandingSpots.clear();
        }
        this.configuredOverworldCruiseY = 0;
        this.configuredOverworldWaypointDistance = 0;
        this.configuredOverworldTerrainClearance = 0;
        this.configuredOverworldWaterDive = false;
        this.landingRetryCooldownTicks = 0;
        destroyBehaviorAsync();
    }

    private ElytraProcess(Baritone baritone, boolean nativeLoaded) {
        super(baritone);
        this.nativeLoaded = nativeLoaded;
        baritone.getGameEventHandler().registerEventListener(this);
    }

    public static IElytraProcess create(final Baritone baritone) {
        return new ElytraProcess(baritone, NetherPathfinderContext.isSupported());
    }

    @Override
    public boolean isActive() {
        return this.behavior != null;
    }

    @Override
    public void resetState() {
        Goal preservedGoal = this.missionGoal;
        BlockPos destination = this.currentDestination();
        this.clearRuntimeState(true);
        if (preservedGoal != null) {
            this.pathTo(preservedGoal);
            this.repackChunks();
        } else if (destination != null) {
            this.pathTo(destination);
            this.repackChunks();
        }
    }

    private static final String AUTO_JUMP_FAILURE_MSG = "Failed to compute a walking path to a takeoff spot. Consider starting from a higher location or near an overhang. You can also begin gliding manually instead.";

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (this.landingRetryCooldownTicks > 0) {
            this.landingRetryCooldownTicks--;
        }
        final long seedSetting = Baritone.settings().elytraNetherSeed.value;
        if (this.behavior.usesNetherPathfinder() && seedSetting != this.behavior.getNetherSeed()) {
            logDirect("Nether seed changed, recalculating path");
            this.resetState();
        }
        if (this.behavior.usesNetherPathfinder() && predictingTerrain != Baritone.settings().elytraPredictTerrain.value) {
            logDirect("elytraPredictTerrain setting changed, recalculating path");
            predictingTerrain = Baritone.settings().elytraPredictTerrain.value;
            this.resetState();
        }
        if (!this.behavior.usesNetherPathfinder() && (
                this.configuredOverworldCruiseY != Baritone.settings().elytraOverworldCruiseY.value
                        || this.configuredOverworldWaypointDistance != Baritone.settings().elytraOverworldWaypointDistance.value
                        || this.configuredOverworldTerrainClearance != Baritone.settings().elytraOverworldTerrainClearance.value
                        || this.configuredOverworldWaterDive != Baritone.settings().elytraOverworldWaterDive.value
        )) {
            logDirect("Overworld elytra planner setting changed, recalculating path");
            this.configuredOverworldCruiseY = Baritone.settings().elytraOverworldCruiseY.value;
            this.configuredOverworldWaypointDistance = Baritone.settings().elytraOverworldWaypointDistance.value;
            this.configuredOverworldTerrainClearance = Baritone.settings().elytraOverworldTerrainClearance.value;
            this.configuredOverworldWaterDive = Baritone.settings().elytraOverworldWaterDive.value;
            this.resetState();
        }

        this.behavior.onTick();

        if (calcFailed) {
            onLostControl();
            logDirect(AUTO_JUMP_FAILURE_MSG);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        boolean safetyLanding = false;
        if (ctx.player().isFallFlying() && shouldLandForSafety()) {
            if (Baritone.settings().elytraAllowEmergencyLand.value) {
                logDirect("Emergency landing - almost out of elytra durability or fireworks");
                safetyLanding = true;
            } else {
                logDirect("almost out of elytra durability or fireworks, but I'm going to continue since elytraAllowEmergencyLand is false");
            }
        }
        if (ctx.player().isFallFlying() && this.state != State.LANDING) {
            final BetterBlockPos last = this.behavior.pathManager.path.getLast();
            final double horizontalSpeed = ctx.playerMotion().multiply(1, 0, 1).length();
            final double downwardSpeed = Math.max(0.0D, -ctx.playerMotion().y);
            final double landingSetupDistance = Mth.clamp(horizontalSpeed * 32.0D + downwardSpeed * 20.0D + 48.0D, 48.0D, 112.0D);
            if (this.landingRetryCooldownTicks <= 0
                    && !this.goingToLandingSpot
                    && this.behavior.pathManager.isComplete()
                    && last != null
                    && ctx.player().position().distanceToSqr(last.getCenter()) < (landingSetupDistance * landingSetupDistance)) {
                logDirect("Path complete, picking a safe landing spot near the goal...");
                BetterBlockPos landingSpot = findGoalLandingSpot();
                if (landingSpot != null) {
                    this.pathToLanding(landingSpot, true);
                } else {
                    logDirect("No safe landing spot found near the goal yet, continuing to orbit the destination");
                }
            }
            if (this.landingRetryCooldownTicks <= 0 && !this.goingToLandingSpot && safetyLanding) {
                logDirect("Picking a nearby safe landing spot...");
                BetterBlockPos landingSpot = findSafeLandingSpot(ctx.playerFeet(), ctx.playerFeet());
                if (landingSpot != null) {
                    this.pathToLanding(landingSpot, false);
                } else {
                    logDirect("No nearby safe landing spot found yet, continuing flight");
                }
            }
            if (this.landingRetryCooldownTicks <= 0 && this.goingToLandingSpot && this.behavior.shouldBeginLanding()) {
                this.state = State.LANDING;
                logDirect("Above the landing spot, landing...");
            }
        }

        if (this.state == State.LANDING) {
            final BetterBlockPos endPos = this.landingSpot;
            if (ctx.player().isFallFlying() && endPos != null) {
                if (this.shouldRetryLanding(endPos)) {
                    logDirect("bad landing spot, trying again...");
                    landingSpotIsBad(endPos);
                }
            }
        }

        if (ctx.player().isFallFlying()) {
            behavior.landingMode = this.state == State.LANDING;
            this.goal = null;
            baritone.getInputOverrideHandler().clearAllKeys();
            behavior.tick();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        } else if (this.state == State.LANDING) {
            if (ctx.playerMotion().multiply(1, 0, 1).length() > 0.001) {
                logDirect("Landed, but still moving, waiting for velocity to die down... ");
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            if (this.arrivalAfterLanding && !this.reachedGoal) {
                Goal handoffGoal = this.missionGoal;
                boolean goalSatisfied = handoffGoal == null || (handoffGoal.isInGoal(ctx.playerFeet()) && handoffGoal.isInGoal(baritone.getPathingBehavior().pathStart()));
                if (!goalSatisfied && handoffGoal != null) {
                    logDirect("Landed safely, resuming ground path to complete the original goal");
                    baritone.getInputOverrideHandler().clearAllKeys();
                    this.onLostControl();
                    baritone.getCustomGoalProcess().setGoalAndPath(handoffGoal);
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                if (Baritone.settings().notificationOnPathComplete.value) {
                    logNotification("Pathing complete", false);
                }
                this.reachedGoal = true;
                if (Baritone.settings().disconnectOnArrival.value) {
                    this.onLostControl();
                    if (ctx.world() instanceof ClientLevel clientLevel) {
                        clientLevel.disconnect(Component.literal("[Baritone] Arrived at goal!"));
                    }
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
            }
            logDirect("Done :)");
            baritone.getInputOverrideHandler().clearAllKeys();
            this.onLostControl();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (this.state == State.FLYING || this.state == State.START_FLYING) {
            this.state = ctx.player().onGround() && this.shouldAutoTakeoff()
                    ? State.LOCATE_JUMP
                    : State.START_FLYING;
        }

        if (this.state == State.LOCATE_JUMP) {
            if (shouldLandForSafety()) {
                logDirect("Not taking off, because elytra durability or fireworks are so low that I would immediately emergency land anyway.");
                onLostControl();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            if (this.goal == null) {
                this.goal = this.createTakeoffGoal();
            }
            final IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
            if (executor != null && executor.getPath().getGoal() == this.goal) {
                final IMovement fall = executor.getPath().movements().stream()
                        .filter(movement -> movement instanceof MovementFall)
                        .findFirst().orElse(null);

                if (fall != null) {
                    final BetterBlockPos from = new BetterBlockPos(
                            (fall.getSrc().x + fall.getDest().x) / 2,
                            (fall.getSrc().y + fall.getDest().y) / 2,
                            (fall.getSrc().z + fall.getDest().z) / 2
                    );
                    this.state = State.PAUSE;
                    behavior.pathManager.pathToDestination(from).whenComplete((result, ex) -> {
                        if (ex == null) {
                            this.state = State.GET_TO_JUMP;
                            return;
                        }
                        onLostControl();
                    });
                } else {
                    onLostControl();
                    logDirect(AUTO_JUMP_FAILURE_MSG);
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
            }
            return new PathingCommandContext(this.goal, PathingCommandType.SET_GOAL_AND_PAUSE, new WalkOffCalculationContext(baritone));
        }

        // yucky
        if (this.state == State.PAUSE) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (this.state == State.GET_TO_JUMP) {
            final IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
            // TODO 1.21.5: replace `ctx.player().getDeltaMovement().y < -0.377` with `ctx.player().fallDistance > 1.0f`
            final boolean canStartFlying = ctx.player().getDeltaMovement().y < -0.377
                    && !isSafeToCancel
                    && executor != null
                    && executor.getPath().movements().get(executor.getPosition()) instanceof MovementFall;

            if (canStartFlying) {
                this.state = State.START_FLYING;
            } else {
                return new PathingCommand(null, PathingCommandType.SET_GOAL_AND_PATH);
            }
        }

        if (this.state == State.START_FLYING) {
            if (!isSafeToCancel) {
                // owned
                baritone.getPathingBehavior().secretInternalSegmentCancel();
            }
            baritone.getInputOverrideHandler().clearAllKeys();
            // TODO 1.21.5: replace `ctx.player().getDeltaMovement().y < -0.377` with `ctx.player().fallDistance > 1.0f`
            if (ctx.player().getDeltaMovement().y < -0.377) {
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            }
        }
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    public void landingSpotIsBad(BetterBlockPos endPos) {
        badLandingSpots.add(endPos);
        goingToLandingSpot = false;
        this.landingSpot = null;
        this.state = State.FLYING;
        this.landingRetryCooldownTicks = LANDING_RETRY_COOLDOWN_TICKS;
    }

    private boolean shouldRetryLanding(BetterBlockPos endPos) {
        if (this.isTouchingLandingSurface(endPos)) {
            return false;
        }
        final Vec3 playerPos = ctx.player().position();
        final double dx = playerPos.x - (endPos.x + 0.5D);
        final double dz = playerPos.z - (endPos.z + 0.5D);
        final double horizontalDistanceSq = dx * dx + dz * dz;
        final double belowPad = endPos.y - playerPos.y;
        if (horizontalDistanceSq <= LANDING_TOUCHDOWN_RADIUS_SQ) {
            return false;
        }
        return belowPad > LANDING_FAILURE_DROP_BLOCKS;
    }

    private boolean isTouchingLandingSurface(BetterBlockPos endPos) {
        if (ctx.player().onGround()) {
            return true;
        }
        final BlockPos feet = ctx.playerFeet();
        if (this.hasFluidAt(feet) || this.hasFluidAt(feet.below())) {
            final double dx = ctx.player().position().x - (endPos.x + 0.5D);
            final double dz = ctx.player().position().z - (endPos.z + 0.5D);
            return dx * dx + dz * dz <= LANDING_TOUCHDOWN_RADIUS_SQ;
        }
        return false;
    }

    private boolean hasFluidAt(BlockPos pos) {
        return !ctx.world().getBlockState(pos).getFluidState().isEmpty();
    }

    private void destroyBehaviorAsync() {
        ElytraBehavior behavior = this.behavior;
        if (behavior != null) {
            this.behavior = null;
            Baritone.getExecutor().execute(behavior::destroy);
        }
    }

    @Override
    public double priority() {
        return 0; // higher priority than CustomGoalProcess
    }

    @Override
    public String displayName0() {
        return "Elytra - " + this.state.description;
    }

    @Override
    public void repackChunks() {
        if (this.behavior != null) {
            this.behavior.repackChunks();
        }
    }

    @Override
    public BlockPos currentDestination() {
        return this.behavior != null ? this.missionDestination : null;
    }

    private boolean shouldAutoTakeoff() {
        return ctx.world().dimension() != Level.NETHER || Baritone.settings().elytraAutoJump.value;
    }

    private Goal createTakeoffGoal() {
        if (ctx.world().dimension() != Level.NETHER) {
            final BlockPos destination = this.currentDestination();
            if (destination != null) {
                return new GoalXZ(destination.getX(), destination.getZ());
            }
            final int minY = ctx.world().dimensionType().minY();
            final int currentY = ctx.playerFeet().getY();
            return new GoalYLevel(Math.max(minY + 1, currentY - 8));
        }
        return new GoalYLevel(31);
    }

    @Override
    public void pathTo(BlockPos destination) {
        this.pathTo0(destination, false, null, true, new GoalBlock(destination));
    }

    private void pathToLanding(BetterBlockPos landingSpot, boolean arrivalAfterLanding) {
        this.pathTo0(landingSpot.above(LANDING_COLUMN_HEIGHT), true, landingSpot, false, this.missionGoal);
        this.arrivalAfterLanding = arrivalAfterLanding;
    }

    private void pathTo0(BlockPos destination, boolean appendDestination, BetterBlockPos landingSpot, boolean clearMission, Goal missionGoal) {
        debug("process pathTo0 start dim=" + ctx.world().dimension() + " dest=" + destination + " append=" + appendDestination);
        if (ctx.player() == null) {
            debug("process pathTo0 abort no player");
            return;
        }
        if (ctx.player().level().dimension() == Level.NETHER && !this.nativeLoaded) {
            debug("process pathTo0 abort native not loaded");
            throw new IllegalStateException("Nether elytra pathfinding requires the native library");
        }
        this.clearRuntimeState(clearMission);
        this.predictingTerrain = Baritone.settings().elytraPredictTerrain.value;
        this.configuredOverworldCruiseY = Baritone.settings().elytraOverworldCruiseY.value;
        this.configuredOverworldWaypointDistance = Baritone.settings().elytraOverworldWaypointDistance.value;
        this.configuredOverworldTerrainClearance = Baritone.settings().elytraOverworldTerrainClearance.value;
        this.configuredOverworldWaterDive = Baritone.settings().elytraOverworldWaterDive.value;
        if (clearMission) {
            this.missionDestination = destination.immutable();
            this.missionGoal = missionGoal;
        }
        debug("process pathTo0 creating behavior");
        this.behavior = new ElytraBehavior(this.baritone, this, destination, appendDestination, landingSpot);
        this.goingToLandingSpot = landingSpot != null;
        this.landingSpot = landingSpot;
        if (ctx.world() != null) {
            debug("process pathTo0 repackChunks");
            this.behavior.repackChunks();
        }
        debug("process pathTo0 behavior.pathTo");
        this.behavior.pathTo();
        debug("process pathTo0 complete");
    }

    @Override
    public void pathTo(Goal iGoal) {
        debug("process pathTo goal start type=" + iGoal.getClass().getSimpleName() + " goal=" + iGoal);
        BlockPos destination = resolveGoalDestination(iGoal);
        if (destination == null) {
            throw new IllegalArgumentException("The goal must resolve to a concrete block position or XZ target");
        }
        final int x = destination.getX();
        final int y = destination.getY();
        final int z = destination.getZ();
        final int minY = ctx.world().dimensionType().minY();
        final int maxY = minY + ctx.world().dimensionType().height();
        if (y < minY || y >= maxY) {
            throw new IllegalArgumentException("The y of the goal is outside the build height");
        }
        debug("process pathTo resolved blockpos=" + x + "," + y + "," + z);
        this.pathTo0(destination, false, null, true, iGoal);
    }

    private BlockPos resolveGoalDestination(Goal goal) {
        Goal concreteGoal = resolveConcreteGoal(goal);
        if (concreteGoal instanceof GoalXZ goalXZ) {
            int y = ctx.world() != null && ctx.world().dimension() == Level.NETHER
                    ? 64
                    : this.resolveOverworldGoalY(goalXZ);
            return new BlockPos(goalXZ.getX(), y, goalXZ.getZ());
        }
        if (concreteGoal instanceof GoalBlock goalBlock) {
            return new BlockPos(goalBlock.x, goalBlock.y, goalBlock.z);
        }
        if (concreteGoal instanceof IGoalRenderPos renderPos) {
            return renderPos.getGoalPos();
        }
        return null;
    }

    private int resolveOverworldGoalY(GoalXZ goalXZ) {
        final int minY = ctx.world().dimensionType().minY();
        final int maxY = minY + ctx.world().dimensionType().height() - 1;
        if (this.missionDestination != null
                && this.missionDestination.getX() == goalXZ.getX()
                && this.missionDestination.getZ() == goalXZ.getZ()) {
            return Mth.clamp(this.missionDestination.getY(), minY + 1, maxY);
        }
        final BlockPos columnPos = new BlockPos(goalXZ.getX(), ctx.playerFeet().getY(), goalXZ.getZ());
        if (ctx.world().hasChunkAt(columnPos)) {
            return Mth.clamp(ctx.world().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, goalXZ.getX(), goalXZ.getZ()), minY + 1, maxY);
        }
        return Mth.clamp(ctx.world().getSeaLevel() + 1, minY + 1, maxY);
    }

    private Goal resolveConcreteGoal(Goal goal) {
        if (goal == null) {
            return null;
        }
        if (goal instanceof GoalXZ || goal instanceof GoalBlock || goal instanceof IGoalRenderPos) {
            return goal;
        }
        if (goal instanceof GoalComposite composite) {
            Goal bestGoal = null;
            double bestHeuristic = Double.POSITIVE_INFINITY;
            for (Goal child : composite.goals()) {
                Goal concreteChild = resolveConcreteGoal(child);
                if (concreteChild == null) {
                    continue;
                }
                double heuristic = concreteChild.heuristic(ctx.playerFeet());
                if (heuristic < bestHeuristic) {
                    bestHeuristic = heuristic;
                    bestGoal = concreteChild;
                }
            }
            return bestGoal;
        }
        return null;
    }

    private boolean shouldLandForSafety() {
        ItemStack chest = ctx.player().getItemBySlot(EquipmentSlot.CHEST);
        if (chest.getItem() != Items.ELYTRA || chest.getMaxDamage() - chest.getDamageValue() < Baritone.settings().elytraMinimumDurability.value) {
            // elytrabehavior replaces when durability <= minimumDurability, so if durability < minimumDurability then we can reasonably assume that the elytra will soon be broken without replacement
            return true;
        }

        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        int qty = 0;
        for (int i = 0; i < 36; i++) {
            if (ElytraBehavior.isFireworks(inv.get(i))) {
                qty += inv.get(i).getCount();
            }
        }
        if (qty <= Baritone.settings().elytraMinFireworksBeforeLanding.value) {
            return true;
        }
        return false;
    }

    @Override
    public boolean isLoaded() {
        return this.nativeLoaded;
    }

    @Override
    public boolean isSafeToCancel() {
        return !this.isActive() || !(this.state == State.FLYING || this.state == State.START_FLYING);
    }

    public enum State {
        LOCATE_JUMP("Finding spot to jump off"),
        PAUSE("Waiting for elytra path"),
        GET_TO_JUMP("Walking to takeoff"),
        START_FLYING("Begin flying"),
        FLYING("Flying"),
        LANDING("Landing");

        public final String description;

        State(String desc) {
            this.description = desc;
        }
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        if (this.behavior != null) this.behavior.onRenderPass(event);
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        if (event.getWorld() != null && event.getState() == EventState.POST) {
            // Exiting the world, just destroy
            destroyBehaviorAsync();
        }
    }

    @Override
    public void onChunkEvent(ChunkEvent event) {
        if (this.behavior != null) this.behavior.onChunkEvent(event);
    }

    @Override
    public void onBlockChange(BlockChangeEvent event) {
        if (this.behavior != null) this.behavior.onBlockChange(event);
    }

    @Override
    public void onReceivePacket(PacketEvent event) {
        if (this.behavior != null) this.behavior.onReceivePacket(event);
    }

    @Override
    public void onPostTick(TickEvent event) {
        IBaritoneProcess procThisTick = baritone.getPathingControlManager().mostRecentInControl().orElse(null);
        if (this.behavior != null && procThisTick == this) this.behavior.onPostTick(event);
    }

    /**
     * Custom calculation context which makes the player fall into lava
     */
    public static final class WalkOffCalculationContext extends CalculationContext {

        public WalkOffCalculationContext(IBaritone baritone) {
            super(baritone, true);
            this.allowFallIntoLava = true;
            this.minFallHeight = baritone.getPlayerContext().world().dimension() == Level.NETHER ? 8 : 3;
            this.maxFallHeightNoWater = 10000;
        }

        @Override
        public double costOfPlacingAt(int x, int y, int z, BlockState current) {
            return COST_INF;
        }

        @Override
        public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
            return COST_INF;
        }

        @Override
        public double placeBucketCost() {
            return COST_INF;
        }
    }

    private boolean isInBounds(BlockPos pos) {
        final int minY = ctx.world().dimensionType().minY();
        final int maxY = minY + ctx.world().dimensionType().height();
        return pos.getY() >= minY && pos.getY() < maxY;
    }

    private boolean isSafeBlock(BlockPos pos) {
        BlockState state = ctx.world().getBlockState(pos);
        Block block = state.getBlock();
        if (ctx.world().dimension() == Level.NETHER) {
            return block == Blocks.NETHERRACK
                    || block == Blocks.GRAVEL
                    || (block == Blocks.NETHER_BRICKS && Baritone.settings().elytraAllowLandOnNetherFortress.value);
        }
        return state.isFaceSturdy(ctx.world(), pos, net.minecraft.core.Direction.UP)
                && state.getFluidState().isEmpty()
                && block != Blocks.MAGMA_BLOCK
                && block != Blocks.CACTUS
                && block != Blocks.COBWEB
                && block != Blocks.POWDER_SNOW
                && block != Blocks.SWEET_BERRY_BUSH
                && block != Blocks.WITHER_ROSE
                && !(block instanceof net.minecraft.world.level.block.BaseFireBlock);
    }

    private boolean isAtEdge(BlockPos pos) {
        return !isSafeBlock(pos.north())
                || !isSafeBlock(pos.south())
                || !isSafeBlock(pos.east())
                || !isSafeBlock(pos.west())
                // corners
                || !isSafeBlock(pos.north().west())
                || !isSafeBlock(pos.north().east())
                || !isSafeBlock(pos.south().west())
                || !isSafeBlock(pos.south().east());
    }

    private boolean isColumnAir(BlockPos landingSpot, int minHeight) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos(landingSpot.getX(), landingSpot.getY(), landingSpot.getZ());
        final int maxY = mut.getY() + minHeight;
        for (int y = mut.getY() + 1; y <= maxY; y++) {
            mut.set(mut.getX(), y, mut.getZ());
            if (!(ctx.world().getBlockState(mut).getBlock() instanceof AirBlock)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasAirBubble(BlockPos pos) {
        final int radius = 4; // Half of the full width, rounded down, as we're counting blocks in each direction from the center
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    mut.set(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
                    if (!(ctx.world().getBlockState(mut).getBlock() instanceof AirBlock)) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private BetterBlockPos checkLandingSpot(BlockPos pos, LongOpenHashSet checkedSpots) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos(pos.getX(), pos.getY(), pos.getZ());
        while (mut.getY() >= ctx.world().dimensionType().minY()) {
            if (checkedSpots.contains(mut.asLong())) {
                return null;
            }
            checkedSpots.add(mut.asLong());
            Block block = ctx.world().getBlockState(mut).getBlock();

            if (isSafeBlock(mut)) {
                if (!isAtEdge(mut)) {
                    return new BetterBlockPos(mut);
                }
                return null;
            } else if (block != Blocks.AIR) {
                return null;
            }
            mut.set(mut.getX(), mut.getY() - 1, mut.getZ());
        }
        return null; // void
    }

    private static final int LANDING_COLUMN_HEIGHT = 15;
    private static final int WATER_DIVE_SEARCH_RADIUS = 32;
    private Set<BetterBlockPos> badLandingSpots = new HashSet<>();

    private BetterBlockPos findGoalLandingSpot() {
        if (this.missionDestination == null) {
            return findSafeLandingSpot(ctx.playerFeet(), ctx.playerFeet());
        }
        final int minY = ctx.world().dimensionType().minY();
        final int maxY = minY + ctx.world().dimensionType().height() - 1;
        final int searchY = Math.max(ctx.playerFeet().getY(), this.missionDestination.getY() + LANDING_COLUMN_HEIGHT);
        final BetterBlockPos searchOrigin = new BetterBlockPos(
                this.missionDestination.getX(),
                Math.min(maxY, Math.max(minY + 1, searchY)),
                this.missionDestination.getZ()
        );
        final BetterBlockPos preferredCenter = new BetterBlockPos(this.missionDestination);
        if (Baritone.settings().elytraOverworldWaterDive.value && ctx.world().dimension() == Level.OVERWORLD) {
            BetterBlockPos waterSpot = findWaterLandingSpot(preferredCenter);
            if (waterSpot != null) {
                return waterSpot;
            }
        }
        BetterBlockPos landingSpot = findSafeLandingSpot(searchOrigin, preferredCenter);
        if (landingSpot != null) {
            return landingSpot;
        }
        return findSafeLandingSpot(ctx.playerFeet(), preferredCenter);
    }

    private BetterBlockPos findWaterLandingSpot(BetterBlockPos preferredCenter) {
        BetterBlockPos best = null;
        int bestDistSq = Integer.MAX_VALUE;
        final int searchY = Math.max(ctx.playerFeet().getY(), preferredCenter.getY() + LANDING_COLUMN_HEIGHT);
        for (int dx = -WATER_DIVE_SEARCH_RADIUS; dx <= WATER_DIVE_SEARCH_RADIUS; dx++) {
            for (int dz = -WATER_DIVE_SEARCH_RADIUS; dz <= WATER_DIVE_SEARCH_RADIUS; dz++) {
                final int x = preferredCenter.x + dx;
                final int z = preferredCenter.z + dz;
                final BlockPos column = new BlockPos(x, searchY, z);
                if (!ctx.world().hasChunkAt(column)) {
                    continue;
                }
                final BetterBlockPos waterSpot = this.findWaterSurfaceAt(x, z, searchY);
                if (waterSpot == null || badLandingSpots.contains(waterSpot)) {
                    continue;
                }
                final int distSq = (waterSpot.x - preferredCenter.x) * (waterSpot.x - preferredCenter.x)
                        + (waterSpot.z - preferredCenter.z) * (waterSpot.z - preferredCenter.z);
                if (distSq < bestDistSq) {
                    best = waterSpot;
                    bestDistSq = distSq;
                }
            }
        }
        return best;
    }

    private BetterBlockPos findWaterSurfaceAt(int x, int z, int startY) {
        final int minY = ctx.world().dimensionType().minY();
        final int maxY = minY + ctx.world().dimensionType().height() - 1;
        final BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos(x, Math.min(startY, maxY), z);
        while (mut.getY() >= minY + 1) {
            final BlockState state = ctx.world().getBlockState(mut);
            if (this.isWaterState(state)) {
                final BlockPos above = mut.above();
                final BlockPos below = mut.below();
                if (ctx.world().getBlockState(above).getBlock() instanceof AirBlock
                        && this.isWaterState(ctx.world().getBlockState(below))
                        && this.hasWaterDiveColumn(mut)) {
                    return new BetterBlockPos(mut);
                }
            } else if (!(state.getBlock() instanceof AirBlock)) {
                return null;
            }
            mut.move(0, -1, 0);
        }
        return null;
    }

    private boolean hasWaterDiveColumn(BlockPos waterSpot) {
        final BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos(waterSpot.getX(), waterSpot.getY(), waterSpot.getZ());
        for (int y = waterSpot.getY() + 1; y <= waterSpot.getY() + LANDING_COLUMN_HEIGHT; y++) {
            mut.set(mut.getX(), y, mut.getZ());
            if (!(ctx.world().getBlockState(mut).getBlock() instanceof AirBlock)) {
                return false;
            }
        }
        return true;
    }

    private boolean isWaterState(BlockState state) {
        return !state.getFluidState().isEmpty() && state.getFluidState().isSource();
    }

    private BetterBlockPos findSafeLandingSpot(BetterBlockPos start, BetterBlockPos preferredCenter) {
        Queue<BetterBlockPos> queue = new PriorityQueue<>(Comparator.<BetterBlockPos>comparingInt(pos -> (pos.x - preferredCenter.x) * (pos.x - preferredCenter.x) + (pos.z - preferredCenter.z) * (pos.z - preferredCenter.z)).thenComparingInt(pos -> -pos.y));
        Set<BetterBlockPos> visited = new HashSet<>();
        LongOpenHashSet checkedPositions = new LongOpenHashSet();
        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            BetterBlockPos pos = queue.poll();
            if (ctx.world().isLoaded(pos) && isInBounds(pos) && ctx.world().getBlockState(pos).getBlock() == Blocks.AIR) {
                BetterBlockPos actualLandingSpot = checkLandingSpot(pos, checkedPositions);
                if (actualLandingSpot != null && isColumnAir(actualLandingSpot, LANDING_COLUMN_HEIGHT) && hasAirBubble(actualLandingSpot.above(LANDING_COLUMN_HEIGHT)) && !badLandingSpots.contains(actualLandingSpot)) {
                    return actualLandingSpot;
                }
                if (visited.add(pos.north())) queue.add(pos.north());
                if (visited.add(pos.east())) queue.add(pos.east());
                if (visited.add(pos.south())) queue.add(pos.south());
                if (visited.add(pos.west())) queue.add(pos.west());
                if (visited.add(pos.above())) queue.add(pos.above());
                if (visited.add(pos.below())) queue.add(pos.below());
            }
        }
        return null;
    }
}
