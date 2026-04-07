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
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.BlockOptionalMeta;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public final class HarvestProcess extends BaritoneProcessHelper {

    private static final double PICKUP_RADIUS_SQ = 6 * 6;

    private BlockOptionalMetaLookup filter;
    private int quantity;
    private List<Item> savedThrowawayItems;

    public HarvestProcess(Baritone baritone) {
        super(baritone);
    }

    public void harvest(int quantity, BlockOptionalMeta... boms) {
        onLostControl();
        this.filter = new BlockOptionalMetaLookup(boms);
        this.quantity = quantity;
        this.savedThrowawayItems = new ArrayList<>(Baritone.settings().acceptableThrowawayItems.value);
        Baritone.settings().acceptableThrowawayItems.value = this.savedThrowawayItems.stream()
                .filter(item -> !this.filter.has(new ItemStack(item)))
                .collect(Collectors.toCollection(ArrayList::new));
        baritone.getMineProcess().mine(quantity, this.filter);
    }

    @Override
    public boolean isActive() {
        return this.filter != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        List<ItemEntity> nearbyDrops = nearbyDrops();
        if (!nearbyDrops.isEmpty()) {
            Goal goal = new GoalComposite(nearbyDrops.stream()
                    .map(Entity::blockPosition)
                    .distinct()
                    .sorted(Comparator.comparingLong(pos -> BetterBlockPos.longHash(pos.getX(), pos.getY(), pos.getZ())))
                    .map(GoalBlock::new)
                    .toArray(Goal[]::new));
            return new PathingCommand(goal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
        if (baritone.getMineProcess().isActive()) {
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        onLostControl();
        return null;
    }

    @Override
    public void onLostControl() {
        if (this.savedThrowawayItems != null) {
            Baritone.settings().acceptableThrowawayItems.value = new ArrayList<>(this.savedThrowawayItems);
        }
        this.savedThrowawayItems = null;
        this.filter = null;
        this.quantity = 0;
        baritone.getMineProcess().cancel();
    }

    @Override
    public double priority() {
        return IBaritoneProcess.DEFAULT_PRIORITY + 0.1D;
    }

    @Override
    public String displayName0() {
        return "Harvest " + filter + (quantity > 0 ? " x" + quantity : "");
    }

    private List<ItemEntity> nearbyDrops() {
        return ctx.entitiesStream()
                .filter(entity -> entity instanceof ItemEntity)
                .map(entity -> (ItemEntity) entity)
                .filter(Entity::isAlive)
                .filter(item -> item.distanceToSqr(ctx.player()) <= PICKUP_RADIUS_SQ)
                .filter(item -> filter.has(item.getItem()))
                .collect(Collectors.toList());
    }
}
