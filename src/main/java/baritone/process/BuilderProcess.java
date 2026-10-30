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
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.IBuilderProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.schematic.*;
import baritone.api.schematic.format.ISchematicFormat;
import baritone.api.utils.*;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.PathingCommandContext;
import baritone.utils.schematic.MapArtSchematic;
import baritone.utils.schematic.SchematicSystem;
import baritone.utils.schematic.SelectionSchematic;
import baritone.utils.schematic.litematica.LitematicaHelper;
import baritone.utils.schematic.schematica.SchematicaHelper;
import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponents;
import baritone.api.utils.Tuple;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

public final class BuilderProcess extends BaritoneProcessHelper implements IBuilderProcess {

    private static final Set<Property<?>> ORIENTATION_PROPS =
            ImmutableSet.of(
                    RotatedPillarBlock.AXIS, HorizontalDirectionalBlock.FACING,
                    HopperBlock.FACING,
                    StairBlock.FACING, StairBlock.HALF, StairBlock.SHAPE,
                    PipeBlock.NORTH, PipeBlock.EAST, PipeBlock.SOUTH, PipeBlock.WEST, PipeBlock.UP,
                    TrapDoorBlock.OPEN, TrapDoorBlock.HALF
            );

    private HashSet<BetterBlockPos> incorrectPositions;
    private LongOpenHashSet observedCompleted; // positions that are completed even if they're out of render distance and we can't make sure right now
    private String name;
    private ISchematic realSchematic;
    private ISchematic schematic;
    private Vec3i origin;
    private int ticks;
    private boolean paused;
    private int layer;
    private int numRepeats;
    private List<BlockState> approxPlaceable;
    public int stopAtHeight = 0;
    private boolean searchShulkers;
    private boolean ignoreBlockedPositions;

    // Shulker restock state machine
    private enum ShulkerRestockState {
        NONE,
        SELECT_SHULKER,    // select the shulker box on hotbar
        PLACE_SHULKER,     // place it down
        WAIT_PLACED,       // wait for the block to appear
        OPEN_SHULKER,      // right-click to open
        WAIT_OPEN,         // wait for container GUI
        TAKE_ITEMS,        // quick-move needed items
        CLOSE_SHULKER,     // close the container
        WAIT_CLOSE,        // wait for container to close
        BREAK_SHULKER,     // mine the shulker back
        COLLECT_SHULKER    // wait for item entity pickup
    }
    private ShulkerRestockState shulkerRestockState = ShulkerRestockState.NONE;
    private BlockPos placedShulkerPos;
    private int shulkerRestockTicks;
    private Block neededBlock; // the block we're restocking for

    public BuilderProcess(Baritone baritone) {
        super(baritone);
    }

    /**
     * Build a selection schematic with extra selection-specific options.
     *
     * @param searchShulkers         search shulker box inventories for materials
     * @param ignoreBlockedPositions skip positions that contain protected/unbreakable blocks
     */
    public void buildSelection(String name, ISchematic schematic, Vec3i origin, boolean searchShulkers, boolean ignoreBlockedPositions) {
        this.searchShulkers = searchShulkers;
        this.ignoreBlockedPositions = ignoreBlockedPositions;
        build(name, schematic, origin);
    }

    @Override
    public void build(String name, ISchematic schematic, Vec3i origin) {
        this.name = name;
        this.schematic = schematic;
        this.realSchematic = null;
        this.searchShulkers = false;
        this.ignoreBlockedPositions = false;
        boolean buildingSelectionSchematic = schematic instanceof SelectionSchematic;
        if (!Baritone.settings().buildSubstitutes.value.isEmpty()) {
            this.schematic = new SubstituteSchematic(this.schematic, Baritone.settings().buildSubstitutes.value);
        }
        if (Baritone.settings().buildSchematicMirror.value != net.minecraft.world.level.block.Mirror.NONE) {
            this.schematic = new MirroredSchematic(this.schematic, Baritone.settings().buildSchematicMirror.value);
        }
        if (Baritone.settings().buildSchematicRotation.value != net.minecraft.world.level.block.Rotation.NONE) {
            this.schematic = new RotatedSchematic(this.schematic, Baritone.settings().buildSchematicRotation.value);
        }
        // TODO this preserves the old behavior, but maybe we should bake the setting value right here
        this.schematic = new MaskSchematic(this.schematic) {
            @Override
            public boolean partOfMask(int x, int y, int z, BlockState current) {
                // partOfMask is only called inside the schematic so desiredState is not null
                return !Baritone.settings().buildSkipBlocks.value.contains(this.desiredState(x, y, z, current, Collections.emptyList()).getBlock());
            }
        };
        int x = origin.getX();
        int y = origin.getY();
        int z = origin.getZ();
        if (Baritone.settings().schematicOrientationX.value) {
            x += schematic.widthX();
        }
        if (Baritone.settings().schematicOrientationY.value) {
            y += schematic.heightY();
        }
        if (Baritone.settings().schematicOrientationZ.value) {
            z += schematic.lengthZ();
        }
        this.origin = new Vec3i(x, y, z);
        this.paused = false;
        this.layer = Baritone.settings().startAtLayer.value;
        this.stopAtHeight = schematic.heightY();
        if (Baritone.settings().buildOnlySelection.value && buildingSelectionSchematic) {  // currently redundant but safer maybe
            if (baritone.getSelectionManager().getSelections().length == 0) {
                logDirect("Poor little kitten forgot to set a selection while BuildOnlySelection is true");
                this.stopAtHeight = 0;
            } else if (Baritone.settings().buildInLayers.value) {
                OptionalInt minim = Stream.of(baritone.getSelectionManager().getSelections()).mapToInt(sel -> sel.min().y).min();
                OptionalInt maxim = Stream.of(baritone.getSelectionManager().getSelections()).mapToInt(sel -> sel.max().y).max();
                if (minim.isPresent() && maxim.isPresent()) {
                    int startAtHeight = Baritone.settings().layerOrder.value ? y + schematic.heightY() - maxim.getAsInt() : minim.getAsInt() - y;
                    this.stopAtHeight = (Baritone.settings().layerOrder.value ? y + schematic.heightY() - minim.getAsInt() : maxim.getAsInt() - y) + 1;
                    this.layer = Math.max(this.layer, startAtHeight / Baritone.settings().layerHeight.value);  // startAtLayer or startAtHeight, whichever is highest
                    logDebug(String.format("Schematic starts at y=%s with height %s", y, schematic.heightY()));
                    logDebug(String.format("Selection starts at y=%s and ends at y=%s", minim.getAsInt(), maxim.getAsInt()));
                    logDebug(String.format("Considering relevant height %s - %s", startAtHeight, this.stopAtHeight));
                }
            }
        }

        this.numRepeats = 0;
        this.observedCompleted = new LongOpenHashSet();
        this.incorrectPositions = null;
    }

    public void resume() {
        paused = false;
    }

    public void pause() {
        paused = true;
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public boolean build(String name, File schematic, Vec3i origin) {
        Optional<ISchematicFormat> format = SchematicSystem.INSTANCE.getByFile(schematic);
        if (!format.isPresent()) {
            return false;
        }
        IStaticSchematic parsed;
        try {
            parsed = format.get().parse(new FileInputStream(schematic));
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        ISchematic schem = applyMapArtAndSelection(origin, parsed);
        build(name, schem, origin);
        return true;
    }

    private ISchematic applyMapArtAndSelection(Vec3i origin, IStaticSchematic parsed) {
        ISchematic schematic = parsed;
        if (Baritone.settings().mapArtMode.value) {
            schematic = new MapArtSchematic(parsed);
        }
        if (Baritone.settings().buildOnlySelection.value) {
            schematic = new SelectionSchematic(schematic, origin, baritone.getSelectionManager().getSelections());
        }
        return schematic;
    }

    @Override
    public void buildOpenSchematic() {
        if (SchematicaHelper.isSchematicaPresent()) {
            Optional<Tuple<IStaticSchematic, BlockPos>> schematic = SchematicaHelper.getOpenSchematic();
            if (schematic.isPresent()) {
                IStaticSchematic raw = schematic.get().getA();
                BlockPos origin = schematic.get().getB();
                ISchematic schem = applyMapArtAndSelection(origin, raw);
                this.build(raw.toString(), schem, origin);
            } else {
                logDirect("No schematic currently open");
            }
        } else {
            logDirect("Schematica is not present");
        }
    }

    @Override
    public void buildOpenLitematic(int i) {
        if (LitematicaHelper.isLitematicaPresent()) {
            //if java.lang.NoSuchMethodError is thrown see comment in SchematicPlacementManager
            if (LitematicaHelper.hasLoadedSchematic(i)) {
                Tuple<IStaticSchematic, Vec3i> schematic = LitematicaHelper.getSchematic(i);
                Vec3i correctedOrigin = schematic.getB();
                ISchematic schematic2 = applyMapArtAndSelection(correctedOrigin, schematic.getA());
                build(schematic.getA().toString(), schematic2, correctedOrigin);
            } else {
                logDirect(String.format("List of placements has no entry %s", i + 1));
            }
        } else {
            logDirect("Litematica is not present");
        }
    }

    public void clearArea(BlockPos corner1, BlockPos corner2) {
        BlockPos origin = new BlockPos(Math.min(corner1.getX(), corner2.getX()), Math.min(corner1.getY(), corner2.getY()), Math.min(corner1.getZ(), corner2.getZ()));
        int widthX = Math.abs(corner1.getX() - corner2.getX()) + 1;
        int heightY = Math.abs(corner1.getY() - corner2.getY()) + 1;
        int lengthZ = Math.abs(corner1.getZ() - corner2.getZ()) + 1;
        build("clear area", new FillSchematic(widthX, heightY, lengthZ, Blocks.AIR.defaultBlockState()), origin);
    }

    @Override
    public List<BlockState> getApproxPlaceable() {
        return new ArrayList<>(approxPlaceable);
    }

    @Override
    public boolean isActive() {
        return schematic != null;
    }

    public BlockState placeAt(int x, int y, int z, BlockState current) {
        if (!isActive()) {
            return null;
        }
        if (!schematic.inSchematic(x - origin.getX(), y - origin.getY(), z - origin.getZ(), current)) {
            return null;
        }
        BlockState state = schematic.desiredState(x - origin.getX(), y - origin.getY(), z - origin.getZ(), current, this.approxPlaceable);
        if (state.getBlock() instanceof AirBlock) {
            return null;
        }
        return state;
    }

    private Optional<Tuple<BetterBlockPos, Rotation>> toBreakNearPlayer(BuilderCalculationContext bcc) {
        BetterBlockPos center = ctx.playerFeet();
        BetterBlockPos pathStart = baritone.getPathingBehavior().pathStart();
        int minDy = ctx.world().getFluidState(center).isEmpty()
                ? (Baritone.settings().breakFromAbove.value ? -1 : 0)
                : -5;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = minDy; dy <= 5; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    if (dy == -1 && x == pathStart.x && z == pathStart.z) {
                        continue; // dont mine what we're supported by, but not directly standing on
                    }
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired == null) {
                        continue; // irrelevant
                    }
                    BlockState curr = bcc.bsi.get0(x, y, z);
                    if (!(curr.getBlock() instanceof AirBlock) && !(curr.getBlock() == Blocks.WATER || curr.getBlock() == Blocks.LAVA) && !valid(curr, desired, false)) {
                        BetterBlockPos pos = new BetterBlockPos(x, y, z);
                        Optional<Rotation> rot = RotationUtils.reachable(ctx, pos, ctx.playerController().getBlockReachDistance());
                        if (rot.isPresent()) {
                            return Optional.of(new Tuple<>(pos, rot.get()));
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    public static class Placement {

        private final int hotbarSelection;
        private final BlockPos placeAgainst;
        private final Direction side;
        private final Rotation rot;

        public Placement(int hotbarSelection, BlockPos placeAgainst, Direction side, Rotation rot) {
            this.hotbarSelection = hotbarSelection;
            this.placeAgainst = placeAgainst;
            this.side = side;
            this.rot = rot;
        }
    }

    private Optional<Placement> searchForPlacables(BuilderCalculationContext bcc, List<BlockState> desirableOnHotbar) {
        BetterBlockPos center = ctx.playerFeet();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -5; dy <= 1; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired == null) {
                        continue; // irrelevant
                    }
                    BlockState curr = bcc.bsi.get0(x, y, z);
                    if (MovementHelper.isReplaceable(x, y, z, curr, bcc.bsi) && !valid(curr, desired, false)) {
                        if (dy == 1 && bcc.bsi.get0(x, y + 1, z).getBlock() instanceof AirBlock) {
                            continue;
                        }
                        if (!dependenciesMet(bcc, x, y, z, desired)) {
                            continue;
                        }
                        desirableOnHotbar.add(desired);
                        Optional<Placement> opt = possibleToPlace(desired, x, y, z, bcc.bsi);
                        if (opt.isPresent()) {
                            return opt;
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private boolean dependenciesMet(BuilderCalculationContext bcc, int x, int y, int z, BlockState desired) {
        if (!(desired.getBlock() instanceof HopperBlock)) {
            return true;
        }
        Direction facing = desired.getValue(HopperBlock.FACING);
        if (facing == Direction.DOWN) {
            return true;
        }
        // Hopper faces horizontally — check if target block exists (any solid block, not just schematic match)
        BlockState target = bcc.bsi.get0(x + facing.getStepX(), y + facing.getStepY(), z + facing.getStepZ());
        return !(target.getBlock() instanceof AirBlock);
    }

    public boolean placementPlausible(BlockPos pos, BlockState state) {
        VoxelShape voxelshape = state.getCollisionShape(ctx.world(), pos);
        return voxelshape.isEmpty() || ctx.world().isUnobstructed(null, voxelshape.move(pos.getX(), pos.getY(), pos.getZ()));
    }

    private Optional<Placement> possibleToPlace(BlockState toPlace, int x, int y, int z, BlockStateInterface bsi) {
        for (Direction against : Direction.values()) {
            BetterBlockPos placeAgainstPos = new BetterBlockPos(x, y, z).relative(against);
            BlockState placeAgainstState = bsi.get0(placeAgainstPos);
            if (MovementHelper.isReplaceable(placeAgainstPos.x, placeAgainstPos.y, placeAgainstPos.z, placeAgainstState, bsi)) {
                continue;
            }
            if (!toPlace.canSurvive(ctx.world(), new BetterBlockPos(x, y, z))) {
                continue;
            }
            if (!placementPlausible(new BetterBlockPos(x, y, z), toPlace)) {
                continue;
            }
            VoxelShape shape = placeAgainstState.getShape(ctx.world(), placeAgainstPos);
            if (shape.isEmpty()) {
                continue;
            }
            AABB aabb = shape.bounds();
            for (Vec3 placementMultiplier : aabbSideMultipliers(against)) {
                double placeX = placeAgainstPos.x + aabb.minX * placementMultiplier.x + aabb.maxX * (1 - placementMultiplier.x);
                double placeY = placeAgainstPos.y + aabb.minY * placementMultiplier.y + aabb.maxY * (1 - placementMultiplier.y);
                double placeZ = placeAgainstPos.z + aabb.minZ * placementMultiplier.z + aabb.maxZ * (1 - placementMultiplier.z);
                Rotation rot = RotationUtils.calcRotationFromVec3d(RayTraceUtils.inferSneakingEyePosition(ctx.player()), new Vec3(placeX, placeY, placeZ), ctx.playerRotations());
                Rotation actualRot = baritone.getLookBehavior().getAimProcessor().peekRotation(rot);
                HitResult result = RayTraceUtils.rayTraceTowards(ctx.player(), actualRot, ctx.playerController().getBlockReachDistance(), true);
                if (result != null && result.getType() == HitResult.Type.BLOCK && ((BlockHitResult) result).getBlockPos().equals(placeAgainstPos) && ((BlockHitResult) result).getDirection() == against.getOpposite()) {
                    OptionalInt hotbar = hasAnyItemThatWouldPlace(toPlace, result, actualRot);
                    if (hotbar.isPresent()) {
                        return Optional.of(new Placement(hotbar.getAsInt(), placeAgainstPos, against.getOpposite(), rot));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private OptionalInt hasAnyItemThatWouldPlace(BlockState desired, HitResult result, Rotation rot) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            float originalYaw = ctx.player().getYRot();
            float originalPitch = ctx.player().getXRot();
            // the state depends on the facing of the player sometimes
            ctx.player().setYRot(rot.getYaw());
            ctx.player().setXRot(rot.getPitch());
            BlockPlaceContext meme = new BlockPlaceContext(new UseOnContext(
                    ctx.world(),
                    ctx.player(),
                    InteractionHand.MAIN_HAND,
                    stack,
                    (BlockHitResult) result
            ) {}); // that {} gives us access to a protected constructor lmfao
            BlockState wouldBePlaced = ((BlockItem) stack.getItem()).getBlock().getStateForPlacement(meme);
            ctx.player().setYRot(originalYaw);
            ctx.player().setXRot(originalPitch);
            if (wouldBePlaced == null) {
                continue;
            }
            if (!meme.canPlace()) {
                continue;
            }
            if (valid(wouldBePlaced, desired, true)) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    private static Vec3[] aabbSideMultipliers(Direction side) {
        switch (side) {
            case UP:
                return new Vec3[]{new Vec3(0.5, 1, 0.5), new Vec3(0.1, 1, 0.5), new Vec3(0.9, 1, 0.5), new Vec3(0.5, 1, 0.1), new Vec3(0.5, 1, 0.9)};
            case DOWN:
                return new Vec3[]{new Vec3(0.5, 0, 0.5), new Vec3(0.1, 0, 0.5), new Vec3(0.9, 0, 0.5), new Vec3(0.5, 0, 0.1), new Vec3(0.5, 0, 0.9)};
            case NORTH:
            case SOUTH:
            case EAST:
            case WEST:
                double x = side.getStepX() == 0 ? 0.5 : (1 + side.getStepX()) / 2D;
                double z = side.getStepZ() == 0 ? 0.5 : (1 + side.getStepZ()) / 2D;
                return new Vec3[]{new Vec3(x, 0.25, z), new Vec3(x, 0.75, z)};
            default: // null
                throw new IllegalStateException("Unexpected side " + side);
        }
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        return onTick(calcFailed, isSafeToCancel, 0);
    }

    private PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel, int recursions) {
        if (recursions > 100) { // onTick calls itself, don't crash
            return new PathingCommand(null, PathingCommandType.SET_GOAL_AND_PATH);
        }
        approxPlaceable = approxPlaceable(36);
        if (baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT)) {
            ticks = 5;
        } else {
            ticks--;
        }
        baritone.getInputOverrideHandler().clearAllKeys();
        if (paused) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        // Handle shulker restock state machine
        if (shulkerRestockState != ShulkerRestockState.NONE) {
            return handleShulkerRestock();
        }
        if (Baritone.settings().buildInLayers.value) {
            if (realSchematic == null) {
                realSchematic = schematic;
            }
            ISchematic realSchematic = this.realSchematic; // wrap this properly, dont just have the inner class refer to the builderprocess.this
            int minYInclusive;
            int maxYInclusive;
            // layer = 0 should be nothing
            // layer = realSchematic.heightY() should be everything
            if (Baritone.settings().layerOrder.value) { // top to bottom
                maxYInclusive = realSchematic.heightY() - 1;
                minYInclusive = realSchematic.heightY() - layer * Baritone.settings().layerHeight.value;
            } else {
                maxYInclusive = layer * Baritone.settings().layerHeight.value - 1;
                minYInclusive = 0;
            }
            schematic = new ISchematic() {
                @Override
                public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
                    return realSchematic.desiredState(x, y, z, current, BuilderProcess.this.approxPlaceable);
                }

                @Override
                public boolean inSchematic(int x, int y, int z, BlockState currentState) {
                    return ISchematic.super.inSchematic(x, y, z, currentState) && y >= minYInclusive && y <= maxYInclusive && realSchematic.inSchematic(x, y, z, currentState);
                }

                @Override
                public void reset() {
                    realSchematic.reset();
                }

                @Override
                public int widthX() {
                    return realSchematic.widthX();
                }

                @Override
                public int heightY() {
                    return realSchematic.heightY();
                }

                @Override
                public int lengthZ() {
                    return realSchematic.lengthZ();
                }
            };
        }
        BuilderCalculationContext bcc = new BuilderCalculationContext();
        if (!recalc(bcc)) {
            if (Baritone.settings().buildInLayers.value && layer * Baritone.settings().layerHeight.value < stopAtHeight) {
                logDirect("Starting layer " + layer);
                layer++;
                return onTick(calcFailed, isSafeToCancel, recursions + 1);
            }
            Vec3i repeat = Baritone.settings().buildRepeat.value;
            int max = Baritone.settings().buildRepeatCount.value;
            numRepeats++;
            if (repeat.equals(new Vec3i(0, 0, 0)) || (max != -1 && numRepeats >= max)) {
                logDirect("Done building");
                if (Baritone.settings().notificationOnBuildFinished.value) {
                    logNotification("Done building", false);
                }
                onLostControl();
                return null;
            }
            // build repeat time
            layer = 0;
            origin = origin.offset(repeat);
            if (!Baritone.settings().buildRepeatSneaky.value) {
                schematic.reset();
            }
            logDirect("Repeating build in vector " + repeat + ", new origin is " + origin);
            return onTick(calcFailed, isSafeToCancel, recursions + 1);
        }
        if (Baritone.settings().distanceTrim.value) {
            trim();
        }

        boolean canInteractNow = canInteractWhileInFluidOrOnGround();
        Optional<Tuple<BetterBlockPos, Rotation>> toBreak = toBreakNearPlayer(bcc);
        if (toBreak.isPresent() && isSafeToCancel && canInteractNow) {
            // we'd like to pause to break this block
            // only change look direction if it's safe (don't want to fuck up an in progress parkour for example
            Rotation rot = toBreak.get().getB();
            BetterBlockPos pos = toBreak.get().getA();
            baritone.getLookBehavior().updateTarget(rot, true);
            MovementHelper.switchToBestToolFor(ctx, bcc.get(pos));
            if (ctx.player().isCrouching()) {
                // really horrible bug where a block is visible for breaking while sneaking but not otherwise
                // so you can't see it, it goes to place something else, sneaks, then the next tick it tries to break
                // and is unable since it's unsneaked in the intermediary tick
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            }
            if (ctx.isLookingAt(pos) || ctx.playerRotations().isReallyCloseTo(rot)) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        List<BlockState> desirableOnHotbar = new ArrayList<>();
        Optional<Placement> toPlace = searchForPlacables(bcc, desirableOnHotbar);
        if (toPlace.isPresent() && isSafeToCancel && canInteractNow && ticks <= 0) {
            Rotation rot = toPlace.get().rot;
            baritone.getLookBehavior().updateTarget(rot, true);
            ctx.player().getInventory().setSelectedSlot(toPlace.get().hotbarSelection);
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            if ((ctx.isLookingAt(toPlace.get().placeAgainst) && ((BlockHitResult) ctx.objectMouseOver()).getDirection().equals(toPlace.get().side)) || ctx.playerRotations().isReallyCloseTo(rot)) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        if (Baritone.settings().allowInventory.value) {
            ArrayList<Integer> usefulSlots = new ArrayList<>();
            List<BlockState> noValidHotbarOption = new ArrayList<>();
            outer:
            for (BlockState desired : desirableOnHotbar) {
                for (int i = 0; i < 9; i++) {
                    if (valid(approxPlaceable.get(i), desired, true)) {
                        usefulSlots.add(i);
                        continue outer;
                    }
                }
                noValidHotbarOption.add(desired);
            }

            outer:
            for (int i = 9; i < 36; i++) {
                for (BlockState desired : noValidHotbarOption) {
                    if (valid(approxPlaceable.get(i), desired, true)) {
                        if (!baritone.getInventoryBehavior().attemptToPutOnHotbar(i, usefulSlots::contains)) {
                            // awaiting inventory move, so pause
                            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                        }
                        break outer;
                    }
                }
            }
        }

        Goal goal = assemble(bcc, approxPlaceable.subList(0, 9));
        if (goal == null) {
            goal = assemble(bcc, approxPlaceable, true); // we're far away, so assume that we have our whole inventory to recalculate placeable properly
            if (goal == null) {
                if (Baritone.settings().skipFailedLayers.value && Baritone.settings().buildInLayers.value && layer * Baritone.settings().layerHeight.value < realSchematic.heightY()) {
                    logDirect("Skipping layer that I cannot construct! Layer #" + layer);
                    layer++;
                    return onTick(calcFailed, isSafeToCancel, recursions + 1);
                }
                logDirect("Missing materials, pausing. resume to resume, cancel to cancel");
                paused = true;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
        // If materials are only in shulkers (not in direct inventory), restock before placement
        if (searchShulkers) {
            Block missing = findBlockOnlyInShulkers(bcc);
            if (missing != null) {
                int shulkerSlot = findShulkerContaining(missing);
                if (shulkerSlot != -1) {
                    logDirect("Restocking " + BlockUtils.blockToString(missing) + " from shulker box");
                    neededBlock = missing;
                    beginShulkerRestock(shulkerSlot);
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
        }
        return new PathingCommandContext(goal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH, bcc);
    }

    private boolean canInteractWhileInFluidOrOnGround() {
        if (ctx.player().onGround()) {
            return true;
        }
        if (ctx.world().getFluidState(ctx.playerFeet()).isEmpty()) {
            return false;
        }
        return ctx.player().getDeltaMovement().y >= -0.1;
    }

    private boolean recalc(BuilderCalculationContext bcc) {
        if (incorrectPositions == null) {
            incorrectPositions = new HashSet<>();
            fullRecalc(bcc);
            if (incorrectPositions.isEmpty()) {
                return false;
            }
        }
        recalcNearby(bcc);
        if (incorrectPositions.isEmpty()) {
            fullRecalc(bcc);
        }
        return !incorrectPositions.isEmpty();
    }

    private void trim() {
        HashSet<BetterBlockPos> copy = new HashSet<>(incorrectPositions);
        copy.removeIf(pos -> pos.distSqr(ctx.player().blockPosition()) > 200);
        if (!copy.isEmpty()) {
            incorrectPositions = copy;
        }
    }

    private void recalcNearby(BuilderCalculationContext bcc) {
        BetterBlockPos center = ctx.playerFeet();
        int radius = Baritone.settings().builderTickScanRadius.value;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired != null) {
                        // we care about this position
                        BetterBlockPos pos = new BetterBlockPos(x, y, z);
                        if (valid(bcc.bsi.get0(x, y, z), desired, false)) {
                            incorrectPositions.remove(pos);
                            observedCompleted.add(BetterBlockPos.longHash(pos));
                        } else {
                            incorrectPositions.add(pos);
                            observedCompleted.remove(BetterBlockPos.longHash(pos));
                        }
                    }
                }
            }
        }
    }

    private void fullRecalc(BuilderCalculationContext bcc) {
        incorrectPositions = new HashSet<>();
        for (int y = 0; y < schematic.heightY(); y++) {
            for (int z = 0; z < schematic.lengthZ(); z++) {
                for (int x = 0; x < schematic.widthX(); x++) {
                    int blockX = x + origin.getX();
                    int blockY = y + origin.getY();
                    int blockZ = z + origin.getZ();
                    BlockState current = bcc.bsi.get0(blockX, blockY, blockZ);
                    if (!schematic.inSchematic(x, y, z, current)) {
                        continue;
                    }
                    if (bcc.bsi.worldContainsLoadedChunk(blockX, blockZ)) { // check if its in render distance, not if its in cache
                        // we can directly observe this block, it is in render distance
                        if (valid(bcc.bsi.get0(blockX, blockY, blockZ), schematic.desiredState(x, y, z, current, this.approxPlaceable), false)) {
                            observedCompleted.add(BetterBlockPos.longHash(blockX, blockY, blockZ));
                        } else {
                            incorrectPositions.add(new BetterBlockPos(blockX, blockY, blockZ));
                            observedCompleted.remove(BetterBlockPos.longHash(blockX, blockY, blockZ));
                            if (incorrectPositions.size() > Baritone.settings().incorrectSize.value) {
                                return;
                            }
                        }
                        continue;
                    }
                    // this is not in render distance
                    if (!observedCompleted.contains(BetterBlockPos.longHash(blockX, blockY, blockZ))) {
                        // and we've never seen this position be correct
                        // therefore mark as incorrect
                        incorrectPositions.add(new BetterBlockPos(blockX, blockY, blockZ));
                        if (incorrectPositions.size() > Baritone.settings().incorrectSize.value) {
                            return;
                        }
                    }
                }
            }
        }
    }

    private Goal assemble(BuilderCalculationContext bcc, List<BlockState> approxPlaceable) {
        return assemble(bcc, approxPlaceable, false);
    }

    private Goal assemble(BuilderCalculationContext bcc, List<BlockState> approxPlaceable, boolean logMissing) {
        List<BetterBlockPos> placeable = new ArrayList<>();
        List<BetterBlockPos> deferredPlaceable = new ArrayList<>();
        List<BetterBlockPos> breakable = new ArrayList<>();
        List<BetterBlockPos> sourceLiquids = new ArrayList<>();
        List<BetterBlockPos> flowingLiquids = new ArrayList<>();
        Set<Block> missingBlocks = new HashSet<>();
        List<BetterBlockPos> outOfBounds = new ArrayList<>();
        incorrectPositions.forEach(pos -> {
            BlockState state = bcc.bsi.get0(pos);
            BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, state);
            if (desired == null) {
                outOfBounds.add(pos);
            } else if (state.getBlock() instanceof AirBlock) {
                if (containsBlockState(approxPlaceable, desired)) {
                    if (dependenciesMet(bcc, pos.x, pos.y, pos.z, desired)) {
                        placeable.add(pos);
                    } else {
                        deferredPlaceable.add(pos);
                    }
                } else {
                    missingBlocks.add(desired.getBlock());
                }
            } else {
                if (state.getBlock() instanceof LiquidBlock) {
                    if (!(desired.getBlock() instanceof AirBlock)) {
                        if (containsBlockState(approxPlaceable, desired)) {
                            if (dependenciesMet(bcc, pos.x, pos.y, pos.z, desired)) {
                                placeable.add(pos);
                            } else {
                                deferredPlaceable.add(pos);
                            }
                        } else {
                            missingBlocks.add(desired.getBlock());
                        }
                    } else {
                        // if the block itself is JUST a liquid (i.e. not just a waterlogged block), we CANNOT break it
                        // TODO for 1.13 make sure that this only matches pure water, not waterlogged blocks
                        if (!MovementHelper.possiblyFlowing(state)) {
                            // if it's a source block then we want to replace it with a throwaway
                            sourceLiquids.add(pos);
                        } else {
                            flowingLiquids.add(pos);
                        }
                    }
                } else {
                    breakable.add(pos);
                }
            }
        });
        incorrectPositions.removeAll(outOfBounds);
        // Deadlock breaker: if nothing to place/break/drain, promote deferred hoppers
        if (placeable.isEmpty() && breakable.isEmpty() && sourceLiquids.isEmpty() && !deferredPlaceable.isEmpty()) {
            placeable.addAll(deferredPlaceable);
        }
        List<Goal> toBreak = new ArrayList<>();
        breakable.forEach(pos -> toBreak.add(breakGoal(pos, bcc)));
        List<Goal> toPlace = new ArrayList<>();
        placeable.forEach(pos -> {
            if (!placeable.contains(pos.below()) && !placeable.contains(pos.below(2))) {
                toPlace.add(placementGoal(pos, bcc));
            }
        });
        sourceLiquids.forEach(pos -> toPlace.add(new GoalBlock(pos.above())));

        if (!toPlace.isEmpty()) {
            return new JankyGoalComposite(new GoalComposite(toPlace.toArray(new Goal[0])), new GoalComposite(toBreak.toArray(new Goal[0])));
        }
        if (toBreak.isEmpty()) {
            if (logMissing && !missingBlocks.isEmpty()) {
                logDirect("Missing: " + missingBlocks.stream()
                        .map(BlockUtils::blockToString)
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .collect(Collectors.joining(", ")));
            }
            if (logMissing && !flowingLiquids.isEmpty()) {
                logDirect("Unreplaceable liquids at at least:");
                logDirect(flowingLiquids.stream()
                        .map(p -> String.format("%s %s %s", p.x, p.y, p.z))
                        .collect(Collectors.joining("\n")));
            }
            return null;
        }
        return new GoalComposite(toBreak.toArray(new Goal[0]));
    }

    public static class JankyGoalComposite implements Goal {

        private final Goal primary;
        private final Goal fallback;

        public JankyGoalComposite(Goal primary, Goal fallback) {
            this.primary = primary;
            this.fallback = fallback;
        }


        @Override
        public boolean isInGoal(int x, int y, int z) {
            return primary.isInGoal(x, y, z) || fallback.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return primary.heuristic(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            JankyGoalComposite goal = (JankyGoalComposite) o;
            return Objects.equals(primary, goal.primary)
                    && Objects.equals(fallback, goal.fallback);
        }

        @Override
        public int hashCode() {
            int hash = -1701079641;
            hash = hash * 1196141026 + primary.hashCode();
            hash = hash * -80327868 + fallback.hashCode();
            return hash;
        }

        @Override
        public String toString() {
            return "JankyComposite Primary: " + primary + " Fallback: " + fallback;
        }
    }

    public static class GoalBreak extends GoalGetToBlock {

        public GoalBreak(BlockPos pos) {
            super(pos);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            // can't stand right on top of a block, that might not work (what if it's unsupported, can't break then)
            if (y > this.y) {
                return false;
            }
            // but any other adjacent works for breaking, including inside or below
            return super.isInGoal(x, y, z);
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalBreak{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 1636324008;
        }
    }

    private Goal placementGoal(BlockPos pos, BuilderCalculationContext bcc) {
        if (!(ctx.world().getBlockState(pos).getBlock() instanceof AirBlock)) {  // TODO can this even happen?
            return new GoalPlace(pos);
        }
        boolean allowSameLevel = !(ctx.world().getBlockState(pos.above()).getBlock() instanceof AirBlock);
        BlockState current = ctx.world().getBlockState(pos);
        for (Direction facing : Movement.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP) {
            //noinspection ConstantConditions
            if (MovementHelper.canPlaceAgainst(ctx, pos.relative(facing)) && placementPlausible(pos, bcc.getSchematic(pos.getX(), pos.getY(), pos.getZ(), current))) {
                return new GoalAdjacent(pos, pos.relative(facing), allowSameLevel);
            }
        }
        return new GoalPlace(pos);
    }

    private Goal breakGoal(BlockPos pos, BuilderCalculationContext bcc) {
        if (Baritone.settings().goalBreakFromAbove.value && bcc.bsi.get0(pos.above()).getBlock() instanceof AirBlock && bcc.bsi.get0(pos.above(2)).getBlock() instanceof AirBlock) { // TODO maybe possible without the up(2) check?
            return new JankyGoalComposite(new GoalBreak(pos), new GoalGetToBlock(pos.above()) {
                @Override
                public boolean isInGoal(int x, int y, int z) {
                    if (y > this.y || (x == this.x && y == this.y && z == this.z)) {
                        return false;
                    }
                    return super.isInGoal(x, y, z);
                }
            });
        }
        return new GoalBreak(pos);
    }

    public static class GoalAdjacent extends GoalGetToBlock {

        private boolean allowSameLevel;
        private BlockPos no;

        public GoalAdjacent(BlockPos pos, BlockPos no, boolean allowSameLevel) {
            super(pos);
            this.no = no;
            this.allowSameLevel = allowSameLevel;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            if (x == this.x && y == this.y && z == this.z) {
                return false;
            }
            if (x == no.getX() && y == no.getY() && z == no.getZ()) {
                return false;
            }
            if (!allowSameLevel && y == this.y - 1) {
                return false;
            }
            if (y < this.y - 1) {
                return false;
            }
            return super.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            // prioritize lower y coordinates
            return this.y * 100 + super.heuristic(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            if (!super.equals(o)) {
                return false;
            }

            GoalAdjacent goal = (GoalAdjacent) o;
            return allowSameLevel == goal.allowSameLevel
                    && Objects.equals(no, goal.no);
        }

        @Override
        public int hashCode() {
            int hash = 806368046;
            hash = hash * 1412661222 + super.hashCode();
            hash = hash * 1730799370 + (int) BetterBlockPos.longHash(no.getX(), no.getY(), no.getZ());
            hash = hash * 260592149 + (allowSameLevel ? -1314802005 : 1565710265);
            return hash;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalAdjacent{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    public static class GoalPlace extends GoalBlock {

        public GoalPlace(BlockPos placeAt) {
            super(placeAt.above());
        }

        @Override
        public double heuristic(int x, int y, int z) {
            // prioritize lower y coordinates
            return this.y * 100 + super.heuristic(x, y, z);
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 1910811835;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalPlace{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    @Override
    public void onLostControl() {
        incorrectPositions = null;
        name = null;
        schematic = null;
        realSchematic = null;
        layer = Baritone.settings().startAtLayer.value;
        numRepeats = 0;
        paused = false;
        observedCompleted = null;
        shulkerRestockState = ShulkerRestockState.NONE;
        placedShulkerPos = null;
        shulkerRestockTicks = 0;
        neededBlock = null;
    }

    @Override
    public String displayName0() {
        return paused ? "Builder Paused" : "Building " + name;
    }

    @Override
    public Optional<Integer> getMinLayer() {
        if (Baritone.settings().buildInLayers.value) {
            return Optional.of(this.layer);
        }
        return Optional.empty();
    }

    @Override
    public Optional<Integer> getMaxLayer() {
        if (Baritone.settings().buildInLayers.value) {
            return Optional.of(this.stopAtHeight);
        }
        return Optional.empty();
    }

    private List<BlockState> approxPlaceable(int size) {
        List<BlockState> result = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                result.add(Blocks.AIR.defaultBlockState());
                continue;
            }
            // <toxic cloud>
            BlockState itemState = ((BlockItem) stack.getItem())
                .getBlock()
                .getStateForPlacement(
                    new BlockPlaceContext(
                        new UseOnContext(ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, stack, new BlockHitResult(new Vec3(ctx.player().position().x, ctx.player().position().y, ctx.player().position().z), Direction.UP, ctx.playerFeet(), false)) {}
                    )
                );
            if (itemState != null) {
                result.add(itemState);
            } else {
                result.add(Blocks.AIR.defaultBlockState());
            }
            // </toxic cloud>
        }
        // When shulker searching is enabled, also include items found inside shulker boxes
        if (searchShulkers) {
            for (BlockState bs : getShulkerContentsAsPlaceable()) {
                if (!containsBlockState(result, bs)) {
                    result.add(bs);
                }
            }
        }
        return result;
    }

    /**
     * Scan all shulker boxes in the player's inventory and return the BlockStates of
     * any block items found inside them.
     */
    private List<BlockState> getShulkerContentsAsPlaceable() {
        List<BlockState> result = new ArrayList<>();
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            if (!(((BlockItem) stack.getItem()).getBlock() instanceof ShulkerBoxBlock)) {
                continue;
            }
            ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
            if (contents == null) {
                continue;
            }
            contents.nonEmptyItemCopyStream().forEach(inner -> {
                if (inner.getItem() instanceof BlockItem) {
                    BlockState state = ((BlockItem) inner.getItem()).getBlock().defaultBlockState();
                    result.add(state);
                }
            });
        }
        return result;
    }

    /**
     * Find the inventory slot of a shulker box that contains the given block.
     * Returns -1 if not found.
     */
    private int findShulkerContaining(Block block) {
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            if (!(((BlockItem) stack.getItem()).getBlock() instanceof ShulkerBoxBlock)) {
                continue;
            }
            ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
            if (contents == null) {
                continue;
            }
            boolean[] found = {false};
            contents.nonEmptyItemCopyStream().forEach(inner -> {
                if (inner.getItem() instanceof BlockItem && ((BlockItem) inner.getItem()).getBlock() == block) {
                    found[0] = true;
                }
            });
            if (found[0]) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Check if a block is available directly in the player's inventory (not inside shulkers).
     */
    private boolean hasBlockInDirectInventory(Block block) {
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.get(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem && ((BlockItem) stack.getItem()).getBlock() == block) {
                return true;
            }
        }
        return false;
    }

    /**
     * Find a block needed for the build that exists in a shulker but not in direct inventory.
     * Called when we have a goal (some things can be placed) but others need restock.
     */
    private Block findBlockOnlyInShulkers(BuilderCalculationContext bcc) {
        if (incorrectPositions == null) return null;
        BetterBlockPos center = ctx.playerFeet();
        // Only check nearby positions — no need to restock for far-away blocks
        for (BetterBlockPos pos : incorrectPositions) {
            if (pos.distSqr(center) > 100) continue; // within ~10 blocks
            BlockState current = bcc.bsi.get0(pos);
            BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, current);
            if (desired == null) continue;
            if ((current.getBlock() instanceof AirBlock || current.getBlock() instanceof LiquidBlock) && !(desired.getBlock() instanceof AirBlock)) {
                Block block = desired.getBlock();
                if (!hasBlockInDirectInventory(block) && findShulkerContaining(block) != -1) {
                    return block;
                }
            }
        }
        return null;
    }

    private int countEmptyInventorySlots() {
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        int count = 0;
        for (int i = 0; i < 36; i++) {
            if (inv.get(i).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private BlockPos findDroppedShulkerNear(BlockPos near) {
        if (near == null) {
            return null;
        }
        double bestDistSq = Double.MAX_VALUE;
        BlockPos bestPos = null;
        for (net.minecraft.world.entity.Entity entity : ((net.minecraft.client.multiplayer.ClientLevel) ctx.world()).entitiesForRendering()) {
            if (entity instanceof net.minecraft.world.entity.item.ItemEntity itemEntity) {
                ItemStack stack = itemEntity.getItem();
                if (!stack.isEmpty() && stack.getItem() instanceof BlockItem
                        && ((BlockItem) stack.getItem()).getBlock() instanceof ShulkerBoxBlock) {
                    double distSq = entity.blockPosition().distSqr(near);
                    if (distSq < 25 && distSq < bestDistSq) { // within 5 blocks of placement
                        bestDistSq = distSq;
                        bestPos = entity.blockPosition();
                    }
                }
            }
        }
        return bestPos;
    }

    private void beginShulkerRestock(int shulkerSlot) {
        // Move shulker to hotbar if needed
        if (shulkerSlot >= 9) {
            // It's in main inventory, need to swap to hotbar first
            baritone.getInventoryBehavior().attemptToPutOnHotbar(shulkerSlot, i -> false);
        }
        shulkerRestockState = ShulkerRestockState.SELECT_SHULKER;
        shulkerRestockTicks = 0;
        placedShulkerPos = null;
    }

    private PathingCommand handleShulkerRestock() {
        int delay = Baritone.settings().selShulkerSearchDelay.value;
        shulkerRestockTicks++;

        switch (shulkerRestockState) {
            case SELECT_SHULKER: {
                // Find the shulker on hotbar
                NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
                int hotbarSlot = -1;
                for (int i = 0; i < 9; i++) {
                    ItemStack stack = inv.get(i);
                    if (!stack.isEmpty() && stack.getItem() instanceof BlockItem
                            && ((BlockItem) stack.getItem()).getBlock() instanceof ShulkerBoxBlock) {
                        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
                        if (contents != null) {
                            boolean[] has = {false};
                            contents.nonEmptyItemCopyStream().forEach(inner -> {
                                if (inner.getItem() instanceof BlockItem && ((BlockItem) inner.getItem()).getBlock() == neededBlock) {
                                    has[0] = true;
                                }
                            });
                            if (has[0]) {
                                hotbarSlot = i;
                                break;
                            }
                        }
                    }
                }
                if (hotbarSlot == -1) {
                    // Shulker not on hotbar yet, check main inventory and swap
                    int mainSlot = findShulkerContaining(neededBlock);
                    if (mainSlot == -1) {
                        logDirect("Shulker restock failed: no shulker with " + BlockUtils.blockToString(neededBlock));
                        shulkerRestockState = ShulkerRestockState.NONE;
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }
                    if (mainSlot >= 9) {
                        baritone.getInventoryBehavior().attemptToPutOnHotbar(mainSlot, i -> false);
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }
                    hotbarSlot = mainSlot;
                }
                ctx.player().getInventory().setSelectedSlot(hotbarSlot);
                shulkerRestockState = ShulkerRestockState.PLACE_SHULKER;
                shulkerRestockTicks = 0;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case PLACE_SHULKER: {
                if (shulkerRestockTicks < delay) {
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                // Find a spot to place the shulker: on top of a solid block near the player
                BlockPos feet = ctx.playerFeet();
                BlockPos placePos = null;
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            BlockPos candidate = feet.offset(dx, dy, dz);
                            if (candidate.equals(feet) || candidate.equals(feet.above())) continue;
                            BlockState stateAt = ctx.world().getBlockState(candidate);
                            BlockState stateBelow = ctx.world().getBlockState(candidate.below());
                            if (stateAt.getBlock() instanceof AirBlock && !(stateBelow.getBlock() instanceof AirBlock) && !(stateBelow.getBlock() instanceof LiquidBlock)) {
                                placePos = candidate;
                                break;
                            }
                        }
                        if (placePos != null) break;
                    }
                    if (placePos != null) break;
                }
                if (placePos == null) {
                    logDirect("Shulker restock failed: no valid placement spot nearby");
                    shulkerRestockState = ShulkerRestockState.NONE;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                placedShulkerPos = placePos;
                // Look at the block below the placement pos and right-click to place
                BlockPos placeAgainst = placePos.below();
                Optional<Rotation> rot = RotationUtils.reachable(ctx, placeAgainst, ctx.playerController().getBlockReachDistance());
                if (rot.isPresent()) {
                    baritone.getLookBehavior().updateTarget(rot.get(), true);
                    if (ctx.isLookingAt(placeAgainst) || ctx.playerRotations().isReallyCloseTo(rot.get())) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                        shulkerRestockState = ShulkerRestockState.WAIT_PLACED;
                        shulkerRestockTicks = 0;
                    }
                } else {
                    logDirect("Shulker restock failed: can't reach placement spot");
                    shulkerRestockState = ShulkerRestockState.NONE;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case WAIT_PLACED: {
                if (shulkerRestockTicks < delay) {
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                if (placedShulkerPos != null && ctx.world().getBlockState(placedShulkerPos).getBlock() instanceof ShulkerBoxBlock) {
                    shulkerRestockState = ShulkerRestockState.OPEN_SHULKER;
                    shulkerRestockTicks = 0;
                } else if (shulkerRestockTicks > 40) {
                    logDirect("Shulker restock failed: shulker didn't place");
                    shulkerRestockState = ShulkerRestockState.NONE;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case OPEN_SHULKER: {
                if (shulkerRestockTicks < delay) {
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                // Right-click the placed shulker to open it
                Optional<Rotation> rot = RotationUtils.reachable(ctx, placedShulkerPos, ctx.playerController().getBlockReachDistance());
                if (rot.isPresent()) {
                    baritone.getLookBehavior().updateTarget(rot.get(), true);
                    if (ctx.isLookingAt(placedShulkerPos) || ctx.playerRotations().isReallyCloseTo(rot.get())) {
                        // Make sure we have an empty hand or at least not a block
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                        shulkerRestockState = ShulkerRestockState.WAIT_OPEN;
                        shulkerRestockTicks = 0;
                    }
                } else {
                    logDirect("Shulker restock failed: can't reach shulker to open");
                    shulkerRestockState = ShulkerRestockState.BREAK_SHULKER;
                    shulkerRestockTicks = 0;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case WAIT_OPEN: {
                if (ctx.player().containerMenu != ctx.player().inventoryMenu) {
                    // Container is open
                    shulkerRestockState = ShulkerRestockState.TAKE_ITEMS;
                    shulkerRestockTicks = 0;
                } else if (shulkerRestockTicks > 40) {
                    logDirect("Shulker restock failed: container didn't open");
                    shulkerRestockState = ShulkerRestockState.BREAK_SHULKER;
                    shulkerRestockTicks = 0;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case TAKE_ITEMS: {
                if (shulkerRestockTicks < delay) {
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                if (ctx.player().containerMenu == ctx.player().inventoryMenu) {
                    // Container closed unexpectedly
                    shulkerRestockState = ShulkerRestockState.BREAK_SHULKER;
                    shulkerRestockTicks = 0;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                // Count empty inventory slots; reserve 1 for the shulker box drop after mining
                int emptySlots = countEmptyInventorySlots();
                if (emptySlots <= 1) {
                    logDirect("Shulker restock: inventory too full, skipping item transfer to reserve slot for shulker drop");
                    shulkerRestockState = ShulkerRestockState.CLOSE_SHULKER;
                    shulkerRestockTicks = 0;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                int slotsAvailable = emptySlots - 1; // reserve 1 for the shulker box itself
                // Quick-move (shift-click) matching items from the shulker (slots 0-26) to inventory
                int containerId = ctx.player().containerMenu.containerId;
                boolean movedAny = false;
                for (int slot = 0; slot < 27 && slotsAvailable > 0; slot++) {
                    ItemStack slotStack = ctx.player().containerMenu.getSlot(slot).getItem();
                    if (!slotStack.isEmpty() && slotStack.getItem() instanceof BlockItem
                            && ((BlockItem) slotStack.getItem()).getBlock() == neededBlock) {
                        ctx.playerController().windowClick(containerId, slot, 0, ContainerInput.QUICK_MOVE, ctx.player());
                        movedAny = true;
                        slotsAvailable--;
                    }
                }
                if (!movedAny) {
                    logDirect("Shulker restock: no matching items found in shulker container");
                }
                shulkerRestockState = ShulkerRestockState.CLOSE_SHULKER;
                shulkerRestockTicks = 0;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case CLOSE_SHULKER: {
                if (shulkerRestockTicks < delay) {
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                // Close the container
                ctx.player().closeContainer();
                shulkerRestockState = ShulkerRestockState.WAIT_CLOSE;
                shulkerRestockTicks = 0;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case WAIT_CLOSE: {
                if (ctx.player().containerMenu == ctx.player().inventoryMenu) {
                    shulkerRestockState = ShulkerRestockState.BREAK_SHULKER;
                    shulkerRestockTicks = 0;
                } else if (shulkerRestockTicks > 20) {
                    ctx.player().closeContainer();
                    shulkerRestockState = ShulkerRestockState.BREAK_SHULKER;
                    shulkerRestockTicks = 0;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case BREAK_SHULKER: {
                if (shulkerRestockTicks < delay) {
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                if (placedShulkerPos == null) {
                    shulkerRestockState = ShulkerRestockState.NONE;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                if (!(ctx.world().getBlockState(placedShulkerPos).getBlock() instanceof ShulkerBoxBlock)) {
                    // Already broken — go collect the drop
                    shulkerRestockState = ShulkerRestockState.COLLECT_SHULKER;
                    shulkerRestockTicks = 0;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                if (shulkerRestockTicks > 200) {
                    logDirect("Shulker restock failed: timed out breaking shulker");
                    shulkerRestockState = ShulkerRestockState.NONE;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                Optional<Rotation> rot = RotationUtils.reachable(ctx, placedShulkerPos, ctx.playerController().getBlockReachDistance());
                if (rot.isPresent()) {
                    baritone.getLookBehavior().updateTarget(rot.get(), true);
                    MovementHelper.switchToBestToolFor(ctx, ctx.world().getBlockState(placedShulkerPos));
                    if (ctx.isLookingAt(placedShulkerPos) || ctx.playerRotations().isReallyCloseTo(rot.get())) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                    }
                }
                // Check if block is gone
                if (ctx.world().getBlockState(placedShulkerPos).getBlock() instanceof AirBlock) {
                    shulkerRestockState = ShulkerRestockState.COLLECT_SHULKER;
                    shulkerRestockTicks = 0;
                }
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            case COLLECT_SHULKER: {
                // Actively walk toward the dropped shulker item entity
                BlockPos dropPos = findDroppedShulkerNear(placedShulkerPos);
                if (dropPos == null) {
                    // Give the item entity time to spawn on the client before concluding it's gone
                    if (shulkerRestockTicks < 20) {
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }
                    // Item entity is gone — either picked up or despawned
                    shulkerRestockState = ShulkerRestockState.NONE;
                    placedShulkerPos = null;
                    neededBlock = null;
                    logDirect("Shulker restock complete");
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                if (shulkerRestockTicks > 100) {
                    logDirect("Shulker restock: timed out collecting dropped shulker");
                    shulkerRestockState = ShulkerRestockState.NONE;
                    placedShulkerPos = null;
                    neededBlock = null;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                // Path toward the item — GoalNear so we get within pickup range
                return new PathingCommand(new GoalNear(dropPos, 0), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
            }
            default:
                shulkerRestockState = ShulkerRestockState.NONE;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
    }

    private static boolean sameBlockstate(BlockState first, BlockState second) {
        if (first.getBlock() != second.getBlock()) {
            return false;
        }
        boolean ignoreDirection = Baritone.settings().buildIgnoreDirection.value;
        List<String> ignoredProps = Baritone.settings().buildIgnoreProperties.value;
        if (!ignoreDirection && ignoredProps.isEmpty()) {
            return first.equals(second); // early return if no properties are being ignored
        }
        Map<Property<?>, Comparable<?>> map1 = first.getValues().collect(Collectors.toMap(Property.Value::property, Property.Value::value));
        Map<Property<?>, Comparable<?>> map2 = second.getValues().collect(Collectors.toMap(Property.Value::property, Property.Value::value));
        for (Property<?> prop : map1.keySet()) {
            if (map1.get(prop) != map2.get(prop)
                    && !(ignoreDirection && ORIENTATION_PROPS.contains(prop))
                    && !ignoredProps.contains(prop.getName())) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsBlockState(Collection<BlockState> states, BlockState state) {
        for (BlockState testee : states) {
            if (sameBlockstate(testee, state)) {
                return true;
            }
        }
        return false;
    }

    private static boolean valid(BlockState current, BlockState desired, boolean itemVerify) {
        if (desired == null) {
            return true;
        }
        if (current.getBlock() instanceof LiquidBlock && Baritone.settings().okIfWater.value) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && desired.getBlock() instanceof AirBlock) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && Baritone.settings().okIfAir.value.contains(desired.getBlock())) {
            return true;
        }
        if (desired.getBlock() instanceof AirBlock && Baritone.settings().buildIgnoreBlocks.value.contains(current.getBlock())) {
            return true;
        }
        if (!(current.getBlock() instanceof AirBlock) && Baritone.settings().buildIgnoreExisting.value && !itemVerify) {
            return true;
        }
        if (Baritone.settings().buildValidSubstitutes.value.getOrDefault(desired.getBlock(), Collections.emptyList()).contains(current.getBlock()) && !itemVerify) {
            return true;
        }
        if (current.equals(desired)) {
            return true;
        }
        return sameBlockstate(current, desired);
    }

    public class BuilderCalculationContext extends CalculationContext {

        private final List<BlockState> placeable;
        private final ISchematic schematic;
        private final int originX;
        private final int originY;
        private final int originZ;

        public BuilderCalculationContext() {
            super(BuilderProcess.this.baritone, true); // wew lad
            this.placeable = approxPlaceable(9);
            this.schematic = BuilderProcess.this.schematic;
            this.originX = origin.getX();
            this.originY = origin.getY();
            this.originZ = origin.getZ();

            this.jumpPenalty += 10;
            this.backtrackCostFavoringCoefficient = 1;
        }

        private BlockState getSchematic(int x, int y, int z, BlockState current) {
            if (schematic.inSchematic(x - originX, y - originY, z - originZ, current)) {
                return schematic.desiredState(x - originX, y - originY, z - originZ, current, BuilderProcess.this.approxPlaceable);
            } else {
                return null;
            }
        }

        @Override
        public double costOfPlacingAt(int x, int y, int z, BlockState current) {
            if (isPossiblyProtected(x, y, z) || !worldBorder.canPlaceAt(x, z)) { // make calculation fail properly if we can't build
                return COST_INF;
            }
            BlockState sch = getSchematic(x, y, z, current);
            if (sch != null) {
                // TODO this can return true even when allowPlace is off.... is that an issue?
                if (sch.getBlock() instanceof AirBlock) {
                    // we want this to be air, but they're asking if they can place here
                    // this won't be a schematic block, this will be a throwaway
                    return placeBlockCost * Baritone.settings().placeIncorrectBlockPenaltyMultiplier.value; // we're going to have to break it eventually
                }
                if (placeable.contains(sch)) {
                    return 0; // thats right we gonna make it FREE to place a block where it should go in a structure
                    // no place block penalty at all 😎
                    // i'm such an idiot that i just tried to copy and paste the epic gamer moment emoji too
                    // get added to unicode when?
                }
                if (!hasThrowaway) {
                    return COST_INF;
                }
                // we want it to be something that we don't have
                // even more of a pain to place something wrong
                return placeBlockCost * 1.5 * Baritone.settings().placeIncorrectBlockPenaltyMultiplier.value;
            } else {
                if (hasThrowaway) {
                    return placeBlockCost;
                } else {
                    return COST_INF;
                }
            }
        }

        @Override
        public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
            if ((!allowBreak && !allowBreakAnyway.contains(current.getBlock())) || isPossiblyProtected(x, y, z)) {
                return COST_INF;
            }
            BlockState sch = getSchematic(x, y, z, current);
            if (sch != null) {
                if (sch.getBlock() instanceof AirBlock) {
                    // it should be air
                    // regardless of current contents, we can break it
                    return 1;
                }
                // it should be a real block
                // is it already that block?
                if (valid(bsi.get0(x, y, z), sch, false)) {
                    return Baritone.settings().breakCorrectBlockPenaltyMultiplier.value;
                } else {
                    // can break if it's wrong
                    // would be great to return less than 1 here, but that would actually make the cost calculation messed up
                    // since we're breaking a block, if we underestimate the cost, then it'll fail when it really takes the correct amount of time
                    return 1;

                }
                // TODO do blocks in render distace only?
                // TODO allow breaking blocks that we have a tool to harvest and immediately place back?
            } else {
                return 1; // why not lol
            }
        }
    }
}
