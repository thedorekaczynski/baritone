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
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.input.Input;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;

public final class AutoEatProcess extends BaritoneProcessHelper {

    private int previousHotbarSlot = -1;
    private int activeFoodSlot = -1;

    public AutoEatProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        if (!Baritone.settings().autoEat.value || ctx.player() == null || ctx.world() == null) {
            return false;
        }
        if (!isBaritoneBusy()) {
            return false;
        }
        if (ctx.player().isUsingItem()) {
            return isHoldingHotbarFood();
        }
        FoodData food = ctx.player().getFoodData();
        return food.getFoodLevel() <= Baritone.settings().autoEatHungerThreshold.value && findHotbarFoodSlot() != -1;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        baritone.getInputOverrideHandler().clearAllKeys();
        if (ctx.player().isUsingItem()) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (!isSafeToCancel) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        int slot = findHotbarFoodSlot();
        if (slot == -1) {
            restorePreviousSlot();
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        if (activeFoodSlot != slot) {
            if (previousHotbarSlot == -1) {
                previousHotbarSlot = ctx.player().getInventory().getSelectedSlot();
            }
            ctx.player().getInventory().setSelectedSlot(slot);
            ctx.playerController().syncHeldItem();
            activeFoodSlot = slot;
        }
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    @Override
    public void onLostControl() {
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        restorePreviousSlot();
    }

    @Override
    public String displayName0() {
        return "Auto Eat Pause";
    }

    @Override
    public double priority() {
        return DEFAULT_PRIORITY + 0.6D;
    }

    @Override
    public boolean isTemporary() {
        return true;
    }

    private int findHotbarFoodSlot() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = ctx.player().getInventory().getItem(i);
            if (isUsableFood(stack)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isHoldingHotbarFood() {
        int selected = ctx.player().getInventory().getSelectedSlot();
        return selected >= 0 && selected < 9 && isUsableFood(ctx.player().getInventory().getItem(selected));
    }

    private boolean isUsableFood(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem().components().has(DataComponents.FOOD);
    }

    private boolean isBaritoneBusy() {
        return baritone.getPathingBehavior().hasPath()
                || baritone.getPathingBehavior().getInProgress().isPresent();
    }

    private void restorePreviousSlot() {
        if (previousHotbarSlot != -1 && ctx.player() != null) {
            ctx.player().getInventory().setSelectedSlot(previousHotbarSlot);
            ctx.playerController().syncHeldItem();
        }
        previousHotbarSlot = -1;
        activeFoodSlot = -1;
    }
}
