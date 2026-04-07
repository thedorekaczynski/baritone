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
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.behavior.InventoryBehavior;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

public final class HostileCombatProcess extends BaritoneProcessHelper {

    private int previousHotbarSlot = -1;
    private int activeWeaponHotbarSlot = -1;

    public HostileCombatProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        if (ctx.player() == null || ctx.world() == null) {
            return false;
        }
        if (shouldRestorePreviousSlot()) {
            return true;
        }
        if (!Baritone.settings().killAuraHostiles.value) {
            return false;
        }
        if (!isBaritoneBusy()) {
            return false;
        }
        return findNearestThreat() != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        Entity target = findNearestThreat();
        if (target == null) {
            restorePreviousSlot();
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        if (!ctx.player().hasLineOfSight(target)) {
            restorePreviousSlot();
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        Rotation rotation = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), target.getEyePosition(), ctx.playerRotations());
        baritone.getLookBehavior().updateTarget(rotation, false);
        baritone.getInputOverrideHandler().clearAllKeys();
        if (!isSafeToCancel) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        InventoryBehavior.ItemSelectionResult selectionResult = selectWeapon();
        if (selectionResult == InventoryBehavior.ItemSelectionResult.CHANGED
                || selectionResult == InventoryBehavior.ItemSelectionResult.PENDING) {
            ctx.playerController().syncHeldItem();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (!isReadyToAttack(target)) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        ctx.playerController().syncHeldItem();
        ctx.minecraft().gameMode.attack(ctx.player(), target);
        ctx.player().swing(InteractionHand.MAIN_HAND);
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    @Override
    public void onLostControl() {
        baritone.getInputOverrideHandler().clearAllKeys();
        restorePreviousSlot();
    }

    @Override
    public String displayName0() {
        return "Hostile Combat Pause";
    }

    @Override
    public double priority() {
        return DEFAULT_PRIORITY + 0.5D;
    }

    @Override
    public boolean isTemporary() {
        return true;
    }

    private Entity findNearestThreat() {
        double range = Baritone.settings().killAuraHostileRange.value;
        double rangeSq = range * range;
        return ctx.entitiesStream()
                .filter(this::isThreat)
                .filter(entity -> entity.distanceToSqr(ctx.player()) <= rangeSq)
                .min(Comparator.comparingDouble(entity -> entity.distanceToSqr(ctx.player())))
                .orElse(null);
    }

    private boolean isReadyToAttack(Entity target) {
        return ctx.player().hasLineOfSight(target)
                && ctx.player().getAttackStrengthScale(0.0F) >= Baritone.settings().killAuraAttackCooldown.value;
    }

    private boolean isThreat(Entity entity) {
        if (entity == null || !entity.isAlive() || entity.equals(ctx.player())) {
            return false;
        }
        if (entity instanceof ZombifiedPiglin) {
            return false;
        }
        if (!(entity instanceof Enemy)) {
            return false;
        }
        if (entity instanceof NeutralMob && entity instanceof Mob mob) {
            return ctx.player().equals(mob.getTarget());
        }
        return true;
    }

    private InventoryBehavior.ItemSelectionResult selectWeapon() {
        Predicate<ItemStack> desired = bestWeaponPredicate();
        if (desired == null) {
            return InventoryBehavior.ItemSelectionResult.NOT_FOUND;
        }
        int selectedBefore = ctx.player().getInventory().getSelectedSlot();
        InventoryBehavior.ItemSelectionResult result = baritone.getInventoryBehavior().selectItem(desired);
        if (result == InventoryBehavior.ItemSelectionResult.CHANGED) {
            if (previousHotbarSlot == -1) {
                previousHotbarSlot = selectedBefore;
            }
            activeWeaponHotbarSlot = ctx.player().getInventory().getSelectedSlot();
        }
        return result;
    }

    private Predicate<ItemStack> bestWeaponPredicate() {
        for (Predicate<ItemStack> preference : weaponPreferences()) {
            if (baritone.getInventoryBehavior().hasItem(preference)) {
                return preference;
            }
        }
        return null;
    }

    private List<Predicate<ItemStack>> weaponPreferences() {
        if (Baritone.settings().killAuraPreferSword.value) {
            return List.of(
                    stack -> stack.is(Items.NETHERITE_SWORD),
                    stack -> stack.is(Items.DIAMOND_SWORD),
                    stack -> stack.is(Items.IRON_SWORD),
                    stack -> stack.is(Items.GOLDEN_SWORD),
                    stack -> stack.is(Items.STONE_SWORD),
                    stack -> stack.is(Items.WOODEN_SWORD),
                    stack -> stack.is(Items.NETHERITE_AXE),
                    stack -> stack.is(Items.DIAMOND_AXE),
                    stack -> stack.is(Items.IRON_AXE),
                    stack -> stack.is(Items.GOLDEN_AXE),
                    stack -> stack.is(Items.STONE_AXE),
                    stack -> stack.is(Items.WOODEN_AXE)
            );
        }
        return List.of(
                stack -> stack.is(Items.NETHERITE_AXE),
                stack -> stack.is(Items.DIAMOND_AXE),
                stack -> stack.is(Items.IRON_AXE),
                stack -> stack.is(Items.GOLDEN_AXE),
                stack -> stack.is(Items.STONE_AXE),
                stack -> stack.is(Items.WOODEN_AXE),
                stack -> stack.is(Items.NETHERITE_SWORD),
                stack -> stack.is(Items.DIAMOND_SWORD),
                stack -> stack.is(Items.IRON_SWORD),
                stack -> stack.is(Items.GOLDEN_SWORD),
                stack -> stack.is(Items.STONE_SWORD),
                stack -> stack.is(Items.WOODEN_SWORD)
        );
    }

    private boolean isBaritoneBusy() {
        return baritone.getPathingBehavior().hasPath()
                || baritone.getPathingBehavior().getInProgress().isPresent();
    }

    private boolean shouldRestorePreviousSlot() {
        return previousHotbarSlot != -1;
    }

    private void restorePreviousSlot() {
        if (ctx.player() == null) {
            previousHotbarSlot = -1;
            activeWeaponHotbarSlot = -1;
            return;
        }
        int selectedSlot = ctx.player().getInventory().getSelectedSlot();
        if (activeWeaponHotbarSlot != -1
                && selectedSlot == activeWeaponHotbarSlot
                && previousHotbarSlot != activeWeaponHotbarSlot) {
            ctx.player().getInventory().setSelectedSlot(previousHotbarSlot);
            ctx.playerController().syncHeldItem();
        }
        previousHotbarSlot = -1;
        activeWeaponHotbarSlot = -1;
    }
}
