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

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.cache.IWorldData;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.ForAxis;
import baritone.api.command.datatypes.ForBlockOptionalMeta;
import baritone.api.command.datatypes.ForDirection;
import baritone.api.command.datatypes.RelativeBlockPos;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.exception.CommandInvalidTypeException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.api.event.events.RenderEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.schematic.*;
import baritone.api.schematic.mask.shape.CylinderMask;
import baritone.api.schematic.mask.shape.SphereMask;
import baritone.api.selection.ISelection;
import baritone.api.selection.ISelectionManager;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.BlockOptionalMeta;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.utils.BlockUtils;
import baritone.utils.BlockStateInterface;
import baritone.utils.IRenderer;
import baritone.utils.schematic.StaticSchematic;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

public class SelCommand extends Command {

    private ISelectionManager manager = baritone.getSelectionManager();
    private BetterBlockPos pos1 = null;
    private ISchematic clipboard = null;
    private Vec3i clipboardOffset = null;

    public SelCommand(IBaritone baritone) {
        super(baritone, "sel", "selection", "s");
        baritone.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
            @Override
            public void onRenderPass(RenderEvent event) {
                if (!Baritone.settings().renderSelectionCorners.value || pos1 == null) {
                    return;
                }
                Color color = Baritone.settings().colorSelectionPos1.value;
                float opacity = Baritone.settings().selectionOpacity.value;
                float lineWidth = Baritone.settings().selectionLineWidth.value;
                boolean ignoreDepth = Baritone.settings().renderSelectionIgnoreDepth.value;
                BufferBuilder bufferBuilder = IRenderer.startLines(color, opacity);
                IRenderer.emitAABB(bufferBuilder, event.getModelViewStack(), new AABB(pos1), lineWidth);
                IRenderer.endLines(bufferBuilder, ignoreDepth);
            }
        });
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        Action action = Action.getByName(args.getString());
        if (action == null) {
            throw new CommandInvalidTypeException(args.consumed(), "an action");
        }
        if (action == Action.POS1 || action == Action.POS2) {
            if (action == Action.POS2 && pos1 == null) {
                throw new CommandInvalidStateException("Set pos1 first before using pos2");
            }
            BetterBlockPos playerPos = ctx.viewerPos();
            BetterBlockPos pos = args.hasAny() ? args.getDatatypePost(RelativeBlockPos.INSTANCE, playerPos) : playerPos;
            args.requireMax(0);
            if (action == Action.POS1) {
                pos1 = pos;
                logDirect("Position 1 has been set");
            } else {
                manager.addSelection(pos1, pos);
                pos1 = null;
                logDirect("Selection added");
            }
        } else if (action == Action.CLEAR) {
            args.requireMax(0);
            pos1 = null;
            logDirect(String.format("Removed %d selections", manager.removeAllSelections().length));
        } else if (action == Action.SAVE) {
            args.requireExactly(1);
            String name = validatedSelectionName(args.getString());
            ISelection[] selections = manager.getSelections();
            if (selections.length == 0) {
                throw new CommandInvalidStateException("No selections to save");
            }
            IWorldData world = requireWorldData();
            boolean replacing = world.getSavedSelection(name) != null;
            world.saveSelection(name, selections);
            logDirect(String.format("%s saved selection '%s' with %d entr%s",
                    replacing ? "Updated" : "Saved",
                    name,
                    selections.length,
                    selections.length == 1 ? "y" : "ies"));
        } else if (action == Action.LOAD) {
            args.requireExactly(1);
            String name = validatedSelectionName(args.getString());
            ISelection[] saved = requireSavedSelection(name);
            int removed = manager.removeAllSelections().length;
            for (ISelection selection : saved) {
                manager.addSelection(selection);
            }
            pos1 = null;
            logDirect(String.format("Loaded %d saved selection entr%s from '%s' (replaced %d current entr%s)",
                    saved.length,
                    saved.length == 1 ? "y" : "ies",
                    name,
                    removed,
                    removed == 1 ? "y" : "ies"));
        } else if (action == Action.LIST) {
            args.requireMax(0);
            IWorldData world = requireWorldData();
            Set<String> names = world.getSavedSelectionNames();
            if (names.isEmpty()) {
                throw new CommandInvalidStateException("No saved selections");
            }
            logDirect("Saved selections:");
            for (String name : names) {
                ISelection[] saved = world.getSavedSelection(name);
                int count = saved == null ? 0 : saved.length;
                logDirect(String.format("%s (%d entr%s)", name, count, count == 1 ? "y" : "ies"));
            }
        } else if (action == Action.DELETE) {
            args.requireExactly(1);
            String name = validatedSelectionName(args.getString());
            IWorldData world = requireWorldData();
            if (!world.removeSavedSelection(name)) {
                throw new CommandInvalidStateException("No saved selection named '" + name + "'");
            }
            logDirect(String.format("Deleted saved selection '%s'", name));
        } else if (action == Action.FARM || action == Action.RANCH) {
            args.requireMax(0);
            ISelection[] selections = manager.getSelections();
            if (selections.length == 0) {
                throw new CommandInvalidStateException("No selections to save");
            }
            String name = action == Action.FARM ? "farm" : "ranch";
            int count = baritone.getCowHuntProcess().protect(name, selections);
            logDirect(String.format("Saved %d protected cowhunt zone(s) as '%s'", count, name));
        } else if (action == Action.UNFARM || action == Action.UNRANCH) {
            args.requireMax(0);
            String name = action == Action.UNFARM ? "farm" : "ranch";
            int count = baritone.getCowHuntProcess().clearProtected(name);
            if (count == 0) {
                throw new CommandInvalidStateException("No protected cowhunt zones saved as '" + name + "'");
            }
            logDirect(String.format("Cleared %d protected cowhunt zone(s) from '%s'", count, name));
        } else if (action == Action.UNDO) {
            args.requireMax(0);
            if (pos1 != null) {
                pos1 = null;
                logDirect("Undid pos1");
            } else {
                ISelection[] selections = manager.getSelections();
                if (selections.length < 1) {
                    throw new CommandInvalidStateException("Nothing to undo!");
                } else {
                    pos1 = manager.removeSelection(selections[selections.length - 1]).pos1();
                    logDirect("Undid pos2");
                }
            }
        } else if (action == Action.LIGHTAURA) {
            BlockOptionalMeta replaces = args.getDatatypeFor(ForBlockOptionalMeta.INSTANCE);
            BlockOptionalMeta type = args.getDatatypeFor(ForBlockOptionalMeta.INSTANCE);
            args.requireMax(0);
            ISelection[] selections = manager.getSelections();
            if (selections.length == 0) {
                throw new CommandInvalidStateException("No selections");
            }
            executeLightAura(selections, replaces, type);
        } else if (action.isFillAction()) {
            BlockOptionalMeta type = action == Action.CLEARAREA
                    ? new BlockOptionalMeta(Blocks.AIR)
                    : args.getDatatypeFor(ForBlockOptionalMeta.INSTANCE);

            final BlockOptionalMetaLookup replaces; // Action.REPLACE
            final Direction.Axis alignment;         // Action.(H)CYLINDER
            if (action == Action.REPLACE) {
                args.requireMin(1);
                List<BlockOptionalMeta> replacesList = new ArrayList<>();
                replacesList.add(type);
                while (args.has(2)) {
                    replacesList.add(args.getDatatypeFor(ForBlockOptionalMeta.INSTANCE));
                }
                type = args.getDatatypeFor(ForBlockOptionalMeta.INSTANCE);
                replaces = new BlockOptionalMetaLookup(replacesList.toArray(new BlockOptionalMeta[0]));
                alignment = null;
            } else if (action == Action.CYLINDER || action == Action.HCYLINDER) {
                args.requireMax(1);
                alignment = args.hasAny() ? args.getDatatypeFor(ForAxis.INSTANCE) : Direction.Axis.Y;
                replaces = null;
            } else {
                args.requireMax(0);
                replaces = null;
                alignment = null;
            }
            ISelection[] selections = manager.getSelections();
            if (selections.length == 0) {
                throw new CommandInvalidStateException("No selections");
            }
            BetterBlockPos origin = selections[0].min();
            CompositeSchematic composite = new CompositeSchematic(0, 0, 0);
            for (ISelection selection : selections) {
                BetterBlockPos min = selection.min();
                origin = new BetterBlockPos(
                        Math.min(origin.x, min.x),
                        Math.min(origin.y, min.y),
                        Math.min(origin.z, min.z)
                );
            }
            for (ISelection selection : selections) {
                Vec3i size = selection.size();
                BetterBlockPos min = selection.min();

                // Java 8 so no switch expressions 😿
                UnaryOperator<ISchematic> create = fill -> {
                    final int w = fill.widthX();
                    final int h = fill.heightY();
                    final int l = fill.lengthZ();

                    switch (action) {
                        case WALLS:
                            return new WallsSchematic(fill);
                        case SHELL:
                            return new ShellSchematic(fill);
                        case REPLACE:
                            return new ReplaceSchematic(fill, replaces);
                        case SPHERE:
                            return MaskSchematic.create(fill, new SphereMask(w, h, l, true).compute());
                        case HSPHERE:
                            return MaskSchematic.create(fill, new SphereMask(w, h, l, false).compute());
                        case CYLINDER:
                            return MaskSchematic.create(fill, new CylinderMask(w, h, l, true, alignment).compute());
                        case HCYLINDER:
                            return MaskSchematic.create(fill, new CylinderMask(w, h, l, false, alignment).compute());
                        default:
                            // Silent fail
                            return fill;
                    }
                };

                ISchematic schematic = create.apply(new FillSchematic(size.getX(), size.getY(), size.getZ(), type));
                composite.put(schematic, min.x - origin.x, min.y - origin.y, min.z - origin.z);
            }
            baritone.getBuilderProcess().build("Fill", composite, origin);
            logDirect("Filling now");
        } else if (action == Action.COPY) {
            BetterBlockPos playerPos = ctx.viewerPos();
            BetterBlockPos pos = args.hasAny() ? args.getDatatypePost(RelativeBlockPos.INSTANCE, playerPos) : playerPos;
            args.requireMax(0);
            ISelection[] selections = manager.getSelections();
            if (selections.length < 1) {
                throw new CommandInvalidStateException("No selections");
            }
            BlockStateInterface bsi = new BlockStateInterface(ctx);
            BetterBlockPos origin = selections[0].min();
            CompositeSchematic composite = new CompositeSchematic(0, 0, 0);
            for (ISelection selection : selections) {
                BetterBlockPos min = selection.min();
                origin = new BetterBlockPos(
                        Math.min(origin.x, min.x),
                        Math.min(origin.y, min.y),
                        Math.min(origin.z, min.z)
                );
            }
            for (ISelection selection : selections) {
                Vec3i size = selection.size();
                BetterBlockPos min = selection.min();
                BlockState[][][] blockstates = new BlockState[size.getX()][size.getZ()][size.getY()];
                for (int x = 0; x < size.getX(); x++) {
                    for (int y = 0; y < size.getY(); y++) {
                        for (int z = 0; z < size.getZ(); z++) {
                            blockstates[x][z][y] = bsi.get0(min.x + x, min.y + y, min.z + z);
                        }
                    }
                }
                ISchematic schematic = new StaticSchematic(blockstates);
                composite.put(schematic, min.x - origin.x, min.y - origin.y, min.z - origin.z);
            }
            clipboard = composite;
            clipboardOffset = origin.subtract(pos);
            logDirect("Selection copied");
        } else if (action == Action.PASTE) {
            BetterBlockPos playerPos = ctx.viewerPos();
            BetterBlockPos pos = args.hasAny() ? args.getDatatypePost(RelativeBlockPos.INSTANCE, playerPos) : playerPos;
            args.requireMax(0);
            if (clipboard == null) {
                throw new CommandInvalidStateException("You need to copy a selection first");
            }
            baritone.getBuilderProcess().build("Fill", clipboard, pos.offset(clipboardOffset));
            logDirect("Building now");
        } else if (action == Action.EXPAND || action == Action.CONTRACT || action == Action.SHIFT) {
            args.requireExactly(3);
            TransformTarget transformTarget = TransformTarget.getByName(args.getString());
            if (transformTarget == null) {
                throw new CommandInvalidStateException("Invalid transform type");
            }
            Direction direction = args.getDatatypeFor(ForDirection.INSTANCE);
            int blocks = args.getAs(Integer.class);
            ISelection[] selections = manager.getSelections();
            if (selections.length < 1) {
                throw new CommandInvalidStateException("No selections found");
            }
            selections = transformTarget.transform(selections);
            for (ISelection selection : selections) {
                if (action == Action.EXPAND) {
                    manager.expand(selection, direction, blocks);
                } else if (action == Action.CONTRACT) {
                    manager.contract(selection, direction, blocks);
                } else {
                    manager.shift(selection, direction, blocks);
                }
            }
            logDirect(String.format("Transformed %d selections", selections.length));
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append(Action.getAllNames())
                    .filterPrefix(args.getString())
                    .sortAlphabetically()
                    .stream();
        } else {
            Action action = Action.getByName(args.getString());
            if (action != null) {
                if (action == Action.POS1 || action == Action.POS2) {
                    if (args.hasAtMost(3)) {
                        return args.tabCompleteDatatype(RelativeBlockPos.INSTANCE);
                    }
                } else if (action == Action.LOAD || action == Action.DELETE) {
                    if (args.hasExactlyOne()) {
                        String prefix = args.peekString().toLowerCase(Locale.US);
                        return requireWorldData().getSavedSelectionNames().stream()
                                .filter(name -> name.toLowerCase(Locale.US).startsWith(prefix))
                                .sorted(String.CASE_INSENSITIVE_ORDER);
                    }
                } else if (action.isFillAction()) {
                    if (args.hasExactlyOne() || action == Action.REPLACE) {
                        while (args.has(2)) {
                            args.get();
                        }
                        return args.tabCompleteDatatype(ForBlockOptionalMeta.INSTANCE);
                    } else if (args.hasExactly(2) && (action == Action.CYLINDER || action == Action.HCYLINDER)) {
                        args.get();
                        return args.tabCompleteDatatype(ForAxis.INSTANCE);
                    }
                } else if (action == Action.LIGHTAURA) {
                    while (args.has(2)) {
                        args.get();
                    }
                    return args.tabCompleteDatatype(ForBlockOptionalMeta.INSTANCE);
                } else if (action == Action.EXPAND || action == Action.CONTRACT || action == Action.SHIFT) {
                    if (args.hasExactlyOne()) {
                        return new TabCompleteHelper()
                                .append(TransformTarget.getAllNames())
                                .filterPrefix(args.getString())
                                .sortAlphabetically()
                                .stream();
                    } else {
                        TransformTarget target = TransformTarget.getByName(args.getString());
                        if (target != null && args.hasExactlyOne()) {
                            return args.tabCompleteDatatype(ForDirection.INSTANCE);
                        }
                    }
                }
            }
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "WorldEdit-like commands";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The sel command allows you to manipulate Baritone's selections, similarly to WorldEdit.",
                "",
                "Using these selections, you can clear areas, fill them with blocks, or something else.",
                "Selections are normally temporary, but you can persist named ones per world with save/load.",
                "",
                "The expand/contract/shift commands use a kind of selector to choose which selections to target. Supported ones are a/all, n/newest, and o/oldest.",
                "",
                "Usage:",
                "> sel pos1/p1/1 - Set position 1 to your current position.",
                "> sel pos1/p1/1 <x> <y> <z> - Set position 1 to a relative position.",
                "> sel pos2/p2/2 - Set position 2 to your current position.",
                "> sel pos2/p2/2 <x> <y> <z> - Set position 2 to a relative position.",
                "",
                "> sel clear/c - Clear the selection.",
                "> sel save <name> - Save the current selections under a persistent per-world name.",
                "> sel load <name> - Replace the current selections with a saved one.",
                "> sel list/ls - List saved selection names for this world.",
                "> sel delete/del <name> - Delete a saved selection by name.",
                "> sel farm - Save the current selections as a protected cowhunt farm zone.",
                "> sel ranch - Save the current selections as a protected cowhunt ranch zone.",
                "> sel unfarm - Remove the saved protected cowhunt farm zone.",
                "> sel unranch - Remove the saved protected cowhunt ranch zone.",
                "> sel undo/u - Undo the last action (setting positions, creating selections, etc.)",
                "> sel set/fill/s/f [block] - Completely fill all selections with a block.",
                "> sel walls/w [block] - Fill in the walls of the selection with a specified block.",
                "> sel shell/shl [block] - The same as walls, but fills in a ceiling and floor too.",
                "> sel sphere/sph [block] - Fills the selection with a sphere bounded by the sides.",
                "> sel hsphere/hsph [block] - The same as sphere, but hollow.",
                "> sel cylinder/cyl [block] <axis> - Fills the selection with a cylinder bounded by the sides, oriented about the given axis. (default=y)",
                "> sel hcylinder/hcyl [block] <axis> - The same as cylinder, but hollow.",
                "> sel cleararea/ca - Basically 'set air'.",
                "> sel replace/r <blocks...> <with> - Replaces blocks with another block.",
                "> sel lightaura/la <replace> <with> - Sparse flush lighting over a selected flat area to prevent mob spawns, with a need/have/short count.",
                "> sel copy/cp <x> <y> <z> - Copy the selected area relative to the specified or your position.",
                "> sel paste/p <x> <y> <z> - Build the copied area relative to the specified or your position.",
                "",
                "> sel expand <target> <direction> <blocks> - Expand the targets.",
                "> sel contract <target> <direction> <blocks> - Contract the targets.",
                "> sel shift <target> <direction> <blocks> - Shift the targets (does not resize)."
        );
    }

    private void executeLightAura(ISelection[] selections, BlockOptionalMeta replaces, BlockOptionalMeta type) throws CommandInvalidStateException {
        BlockState lightState = type.getAnyBlockState();
        if (lightState == null) {
            throw new CommandInvalidStateException("Invalid light block");
        }
        int lightEmission = lightState.getLightEmission();
        if (lightEmission <= 0) {
            throw new CommandInvalidStateException("Target block does not emit light");
        }
        for (ISelection selection : selections) {
            if (selection.size().getY() != 1) {
                throw new CommandInvalidStateException("lightaura requires 1-block-tall selections");
            }
        }

        BlockStateInterface bsi = new BlockStateInterface(ctx);
        List<BlockPos> candidates = new ArrayList<>();
        for (ISelection selection : selections) {
            BetterBlockPos min = selection.min();
            Vec3i size = selection.size();
            for (int x = 0; x < size.getX(); x++) {
                for (int z = 0; z < size.getZ(); z++) {
                    BlockPos pos = new BlockPos(min.x + x, min.y, min.z + z);
                    if (replaces.matches(bsi.get0(pos))) {
                        candidates.add(pos.immutable());
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            throw new CommandInvalidStateException("Selection has no matching blocks to replace");
        }

        int requiredBlockLight = 1;
        List<BlockPos> uncovered = new ArrayList<>();
        for (BlockPos pos : candidates) {
            if (!coveredByExistingLight(pos, lightEmission, requiredBlockLight)) {
                uncovered.add(pos);
            }
        }

        List<BlockPos> chosen = new ArrayList<>();
        int maxDistance = Math.max(0, lightEmission - requiredBlockLight);
        while (!uncovered.isEmpty()) {
            BlockPos best = null;
            int bestCoverage = 0;
            for (BlockPos candidate : candidates) {
                if (!replaces.matches(bsi.get0(candidate))) {
                    continue;
                }
                if (!canPlaceLightAuraBlock(candidate, lightState)) {
                    continue;
                }
                int coverage = 0;
                for (BlockPos risk : uncovered) {
                    if (candidate.distManhattan(risk) <= maxDistance) {
                        coverage++;
                    }
                }
                if (coverage > bestCoverage) {
                    best = candidate;
                    bestCoverage = coverage;
                }
            }
            if (best == null || bestCoverage == 0) {
                throw new CommandInvalidStateException("Unable to find enough valid light positions in the selection");
            }
            chosen.add(best);
            BlockPos chosenPos = best;
            uncovered.removeIf(risk -> chosenPos.distManhattan(risk) <= maxDistance);
        }

        if (chosen.isEmpty()) {
            logDirect("No additional lights needed.");
            return;
        }

        String blockName = BlockUtils.blockToString(type.getBlock());
        int available = ctx.player().getInventory().getNonEquipmentItems().stream()
                .filter(type::matches)
                .mapToInt(ItemStack::getCount)
                .sum();
        if (available >= chosen.size()) {
            logDirect(String.format("Need %d %s, have %d.", chosen.size(), blockName, available));
        } else {
            logDirect(String.format("Need %d %s, have %d, short %d.", chosen.size(), blockName, available, chosen.size() - available));
        }

        BetterBlockPos origin = new BetterBlockPos(
                chosen.stream().mapToInt(BlockPos::getX).min().orElseThrow(),
                chosen.stream().mapToInt(BlockPos::getY).min().orElseThrow(),
                chosen.stream().mapToInt(BlockPos::getZ).min().orElseThrow()
        );
        CompositeSchematic composite = new CompositeSchematic(0, 0, 0);
        StaticSchematic lightBlock = new StaticSchematic(new BlockState[][][]{{{lightState}}});
        for (BlockPos pos : chosen) {
            composite.put(lightBlock, pos.getX() - origin.x, pos.getY() - origin.y, pos.getZ() - origin.z);
        }
        baritone.getBuilderProcess().build("LightAura", composite, origin);
        logDirect("Lighting now");
    }

    private boolean canPlaceLightAuraBlock(BlockPos pos, BlockState lightState) {
        BlockState current = ctx.world().getBlockState(pos);
        if (current.getDestroySpeed(ctx.world(), pos) < 0.0F) {
            return false;
        }
        if (!ctx.world().getFluidState(pos).isEmpty() || !ctx.world().getFluidState(pos.above()).isEmpty()) {
            return false;
        }
        return lightState.canSurvive(ctx.world(), pos);
    }

    private boolean coveredByExistingLight(BlockPos targetPos, int lightEmission, int requiredBlockLight) {
        return ctx.world().getBrightness(LightLayer.BLOCK, targetPos) >= requiredBlockLight;
    }

    enum Action {
        POS1("pos1", "p1", "1"),
        POS2("pos2", "p2", "2"),
        CLEAR("clear", "c"),
        SAVE("save"),
        LOAD("load"),
        LIST("list", "ls"),
        DELETE("delete", "del", "remove", "rm"),
        FARM("farm"),
        RANCH("ranch"),
        UNFARM("unfarm"),
        UNRANCH("unranch"),
        UNDO("undo", "u"),
        SET("set", "fill", "s", "f"),
        WALLS("walls", "w"),
        SHELL("shell", "shl"),
        SPHERE("sphere", "sph"),
        HSPHERE("hsphere", "hsph"),
        CYLINDER("cylinder", "cyl"),
        HCYLINDER("hcylinder", "hcyl"),
        CLEARAREA("cleararea", "ca"),
        REPLACE("replace", "r"),
        LIGHTAURA("lightaura", "la"),
        EXPAND("expand", "ex"),
        COPY("copy", "cp"),
        PASTE("paste", "p"),
        CONTRACT("contract", "ct"),
        SHIFT("shift", "sh");
        private final String[] names;

        Action(String... names) {
            this.names = names;
        }

        public static Action getByName(String name) {
            for (Action action : Action.values()) {
                for (String alias : action.names) {
                    if (alias.equalsIgnoreCase(name)) {
                        return action;
                    }
                }
            }
            return null;
        }

        public static String[] getAllNames() {
            Set<String> names = new HashSet<>();
            for (Action action : Action.values()) {
                names.addAll(Arrays.asList(action.names));
            }
            return names.toArray(new String[0]);
        }

        public final boolean isFillAction() {
            return this == SET
                    || this == WALLS
                    || this == SHELL
                    || this == SPHERE
                    || this == HSPHERE
                    || this == CYLINDER
                    || this == HCYLINDER
                    || this == CLEARAREA
                    || this == REPLACE;
        }
    }

    enum TransformTarget {
        ALL(sels -> sels, "all", "a"),
        NEWEST(sels -> new ISelection[]{sels[sels.length - 1]}, "newest", "n"),
        OLDEST(sels -> new ISelection[]{sels[0]}, "oldest", "o");
        private final Function<ISelection[], ISelection[]> transform;
        private final String[] names;

        TransformTarget(Function<ISelection[], ISelection[]> transform, String... names) {
            this.transform = transform;
            this.names = names;
        }

        public ISelection[] transform(ISelection[] selections) {
            return transform.apply(selections);
        }

        public static TransformTarget getByName(String name) {
            for (TransformTarget target : TransformTarget.values()) {
                for (String alias : target.names) {
                    if (alias.equalsIgnoreCase(name)) {
                        return target;
                    }
                }
            }
            return null;
        }

        public static String[] getAllNames() {
            Set<String> names = new HashSet<>();
            for (TransformTarget target : TransformTarget.values()) {
                names.addAll(Arrays.asList(target.names));
            }
            return names.toArray(new String[0]);
        }
    }

    private IWorldData requireWorldData() throws CommandInvalidStateException {
        IWorldData world = baritone.getWorldProvider().getCurrentWorld();
        if (world == null) {
            throw new CommandInvalidStateException("No world loaded");
        }
        return world;
    }

    private ISelection[] requireSavedSelection(String name) throws CommandInvalidStateException {
        ISelection[] saved = requireWorldData().getSavedSelection(name);
        if (saved == null) {
            throw new CommandInvalidStateException("No saved selection named '" + name + "'");
        }
        if (saved.length == 0) {
            throw new CommandInvalidStateException("Saved selection '" + name + "' is empty");
        }
        return saved;
    }

    private static String validatedSelectionName(String raw) throws CommandInvalidStateException {
        String name = raw.trim();
        if (name.isEmpty()) {
            throw new CommandInvalidStateException("Selection name cannot be empty");
        }
        return name;
    }
}
