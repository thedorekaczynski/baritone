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
import baritone.api.Settings;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.ISentryProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.VecUtils;
import baritone.behavior.InventoryBehavior;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
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
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;

public final class SentryProcess extends BaritoneProcessHelper implements ISentryProcess {

    private BetterBlockPos firstPoint;
    private BetterBlockPos secondPoint;
    private boolean running;
    private Phase phase = Phase.PATROLLING;
    private int targetIndex;
    private int holdTicksRemaining;
    private int holdTicksTotal;
    private BetterBlockPos autoAnchor;
    private int autoRadius;
    private boolean autoMode;
    private UUID lastReportedThreat;
    private int lastThreatReportTick = Integer.MIN_VALUE;
    private int previousHotbarSlot = -1;
    private int activeWeaponHotbarSlot = -1;

    public SentryProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void start(BetterBlockPos first, BetterBlockPos second) {
        this.firstPoint = first;
        this.secondPoint = second;
        this.autoAnchor = null;
        this.autoRadius = 0;
        this.autoMode = false;
        this.running = true;
        resetState();
    }

    @Override
    public boolean startAuto(BetterBlockPos anchor, int radius) {
        int normalizedRadius = Math.max(6, radius);
        BetterBlockPos[] patrol = chooseAutoPatrolPoints(anchor, normalizedRadius);
        if (patrol == null) {
            return false;
        }
        this.firstPoint = patrol[0];
        this.secondPoint = patrol[1];
        this.autoAnchor = anchor;
        this.autoRadius = normalizedRadius;
        this.autoMode = true;
        this.running = true;
        resetState();
        return true;
    }

    @Override
    public void resume() {
        if (!hasRoute()) {
            return;
        }
        this.running = true;
        resetState();
    }

    @Override
    public void stop() {
        this.running = false;
        this.phase = Phase.PATROLLING;
        this.holdTicksRemaining = 0;
        this.holdTicksTotal = 0;
        this.lastReportedThreat = null;
        this.lastThreatReportTick = Integer.MIN_VALUE;
        baritone.getInputOverrideHandler().clearAllKeys();
        restorePreviousSlot();
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }

    @Override
    public BetterBlockPos getFirstPoint() {
        return this.firstPoint;
    }

    @Override
    public BetterBlockPos getSecondPoint() {
        return this.secondPoint;
    }

    @Override
    public BetterBlockPos getCurrentTarget() {
        if (!hasRoute()) {
            return null;
        }
        return this.targetIndex == 0 ? this.firstPoint : this.secondPoint;
    }

    @Override
    public BetterBlockPos getAutoAnchor() {
        return this.autoAnchor;
    }

    @Override
    public int getAutoRadius() {
        return this.autoRadius;
    }

    @Override
    public boolean isAutoMode() {
        return this.autoMode;
    }

    @Override
    public boolean isHoldingPosition() {
        return this.running && this.phase == Phase.HOLDING;
    }

    @Override
    public boolean isActive() {
        return this.running && hasRoute() && ctx.player() != null && ctx.world() != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        Entity threat = findNearestThreat();
        reportThreat(threat);

        if (this.phase == Phase.HOLDING) {
            baritone.getInputOverrideHandler().clearAllKeys();
            aimForGuarding(threat);
            if (tryAttackThreat(threat, isSafeToCancel)) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            if (this.holdTicksRemaining-- > 0) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            this.phase = Phase.PATROLLING;
            this.targetIndex ^= 1;
        }

        GoalNear goal = currentGoal();
        if (goal == null) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (calcFailed) {
            report(String.format(Locale.US, "Sentry failed to reach patrol point %s", formatPos(getCurrentTarget())));
            beginHolding();
            aimForGuarding(threat);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (goal.isInGoal(ctx.playerFeet())) {
            beginHolding();
            aimForGuarding(threat);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (tryAttackThreat(threat, isSafeToCancel)) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        return new PathingCommand(goal, PathingCommandType.SET_GOAL_AND_PATH);
    }

    @Override
    public void onLostControl() {
        stop();
    }

    @Override
    public String displayName0() {
        BetterBlockPos target = getCurrentTarget();
        if (target == null) {
            return "Sentry";
        }
        String action = this.phase == Phase.HOLDING ? "Holding" : "Patrolling";
        return String.format(Locale.US, "Sentry %s %s", action, formatPos(target));
    }

    private void resetState() {
        this.phase = Phase.PATROLLING;
        this.holdTicksRemaining = 0;
        this.holdTicksTotal = Math.max(1, Baritone.settings().sentryHoldTicks.value);
        this.lastReportedThreat = null;
        this.lastThreatReportTick = Integer.MIN_VALUE;
        this.targetIndex = pickStartingTarget();
    }

    private int pickStartingTarget() {
        if (ctx.player() == null) {
            return 0;
        }
        GoalNear firstGoal = new GoalNear(this.firstPoint, Math.max(0, Baritone.settings().sentryArrivalRadius.value));
        if (firstGoal.isInGoal(ctx.playerFeet())) {
            return 1;
        }
        GoalNear secondGoal = new GoalNear(this.secondPoint, Math.max(0, Baritone.settings().sentryArrivalRadius.value));
        if (secondGoal.isInGoal(ctx.playerFeet())) {
            return 0;
        }
        double firstDistance = ctx.playerFeet().distSqr(this.firstPoint);
        double secondDistance = ctx.playerFeet().distSqr(this.secondPoint);
        return firstDistance <= secondDistance ? 0 : 1;
    }

    private GoalNear currentGoal() {
        BetterBlockPos target = getCurrentTarget();
        if (target == null) {
            return null;
        }
        return new GoalNear(target, Math.max(0, Baritone.settings().sentryArrivalRadius.value));
    }

    private void beginHolding() {
        this.phase = Phase.HOLDING;
        this.holdTicksTotal = Math.max(1, Baritone.settings().sentryHoldTicks.value);
        this.holdTicksRemaining = this.holdTicksTotal;
    }

    private void aimForGuarding(Entity threat) {
        drawWeapon();
        if (threat != null && ctx.player().hasLineOfSight(threat)) {
            Rotation rotation = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), threat.getEyePosition(), ctx.playerRotations());
            baritone.getLookBehavior().updateTarget(rotation, false);
            return;
        }
        baritone.getLookBehavior().updateTarget(scanRotation(), false);
    }

    private Rotation scanRotation() {
        BetterBlockPos lookToward = this.targetIndex == 0 ? this.secondPoint : this.firstPoint;
        Rotation center = lookToward == null
                ? ctx.playerRotations()
                : RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(lookToward), ctx.playerRotations());
        int steps = Math.max(1, Baritone.settings().sentryScanSteps.value);
        if (steps == 1) {
            return new Rotation(center.getYaw(), ctx.playerRotations().getPitch());
        }
        int completed = Math.max(0, this.holdTicksTotal - this.holdTicksRemaining);
        int stepIndex = Math.min(steps - 1, completed * steps / Math.max(1, this.holdTicksTotal));
        float angle = Math.max(0F, Baritone.settings().sentryScanAngle.value);
        float yawOffset = -angle + (2F * angle * stepIndex / (steps - 1));
        return new Rotation(center.getYaw() + yawOffset, ctx.playerRotations().getPitch());
    }

    private boolean tryAttackThreat(Entity threat, boolean isSafeToCancel) {
        if (!Baritone.settings().sentryAutoAttackHostiles.value || threat == null || !ctx.player().hasLineOfSight(threat)) {
            return false;
        }
        Rotation rotation = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), threat.getEyePosition(), ctx.playerRotations());
        baritone.getLookBehavior().updateTarget(rotation, false);
        if (!isSafeToCancel) {
            return false;
        }
        baritone.getInputOverrideHandler().clearAllKeys();
        InventoryBehavior.ItemSelectionResult selectionResult = selectWeapon();
        if (selectionResult == InventoryBehavior.ItemSelectionResult.CHANGED
                || selectionResult == InventoryBehavior.ItemSelectionResult.PENDING) {
            ctx.playerController().syncHeldItem();
            return true;
        }
        if (!isReadyToAttack(threat)) {
            return true;
        }
        ctx.playerController().syncHeldItem();
        ctx.minecraft().gameMode.attack(ctx.player(), threat);
        ctx.player().swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private void drawWeapon() {
        if (selectWeapon() == InventoryBehavior.ItemSelectionResult.CHANGED) {
            ctx.playerController().syncHeldItem();
        }
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

    private boolean isReadyToAttack(Entity target) {
        return ctx.player().hasLineOfSight(target)
                && ctx.player().getAttackStrengthScale(0.0F) >= Baritone.settings().killAuraAttackCooldown.value;
    }

    private InventoryBehavior.ItemSelectionResult selectWeapon() {
        Predicate<ItemStack> desired = bestWeaponPredicate();
        if (desired == null) {
            return InventoryBehavior.ItemSelectionResult.NOT_FOUND;
        }
        int selectedBefore = ctx.player().getInventory().getSelectedSlot();
        InventoryBehavior.ItemSelectionResult result = baritone.getInventoryBehavior().selectItem(desired);
        if (result == InventoryBehavior.ItemSelectionResult.CHANGED) {
            if (this.previousHotbarSlot == -1) {
                this.previousHotbarSlot = selectedBefore;
            }
            this.activeWeaponHotbarSlot = ctx.player().getInventory().getSelectedSlot();
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
        if (Baritone.settings().sentryPreferSword.value) {
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

    private void restorePreviousSlot() {
        if (ctx.player() == null) {
            this.previousHotbarSlot = -1;
            this.activeWeaponHotbarSlot = -1;
            return;
        }
        int selectedSlot = ctx.player().getInventory().getSelectedSlot();
        if (this.activeWeaponHotbarSlot != -1
                && selectedSlot == this.activeWeaponHotbarSlot
                && this.previousHotbarSlot != this.activeWeaponHotbarSlot) {
            ctx.player().getInventory().setSelectedSlot(this.previousHotbarSlot);
            ctx.playerController().syncHeldItem();
        }
        this.previousHotbarSlot = -1;
        this.activeWeaponHotbarSlot = -1;
    }

    private void reportThreat(Entity threat) {
        if (threat == null) {
            this.lastReportedThreat = null;
            return;
        }
        if (Baritone.settings().sentryReportMode.value == Settings.SentryReportMode.OFF) {
            return;
        }
        int now = ctx.player().tickCount;
        UUID threatId = threat.getUUID();
        if (threatId.equals(this.lastReportedThreat) && now - this.lastThreatReportTick < 100) {
            return;
        }
        this.lastReportedThreat = threatId;
        this.lastThreatReportTick = now;
        report(String.format(
                Locale.US,
                "Sentry spotted %s %.1fm %s",
                threat.getType().getDescription().getString().toLowerCase(Locale.US),
                Math.sqrt(threat.distanceToSqr(ctx.player())),
                relativeDirection(threat)
        ));
    }

    private String relativeDirection(Entity threat) {
        Rotation rotation = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), threat.getEyePosition(), ctx.playerRotations());
        float delta = Mth.wrapDegrees(rotation.getYaw() - ctx.playerRotations().getYaw());
        if (Math.abs(delta) <= 25F) {
            return "ahead";
        }
        if (Math.abs(delta) >= 140F) {
            return "behind";
        }
        return delta > 0 ? "left" : "right";
    }

    private void report(String message) {
        switch (Baritone.settings().sentryReportMode.value) {
            case OFF:
                return;
            case CHAT:
                if (ctx.minecraft().getConnection() != null) {
                    ctx.minecraft().getConnection().sendChat(message);
                } else {
                    logDirect(message);
                }
                return;
            case LOG:
            default:
                logDirect(message);
        }
    }

    private String formatPos(BetterBlockPos pos) {
        return pos == null
                ? "(unset)"
                : String.format(Locale.US, "(%d, %d, %d)", pos.x, pos.y, pos.z);
    }

    private BetterBlockPos[] chooseAutoPatrolPoints(BetterBlockPos anchor, int radius) {
        BetterBlockPos north = findFurthestPatrolPoint(anchor, Direction.NORTH, radius);
        BetterBlockPos south = findFurthestPatrolPoint(anchor, Direction.SOUTH, radius);
        BetterBlockPos east = findFurthestPatrolPoint(anchor, Direction.EAST, radius);
        BetterBlockPos west = findFurthestPatrolPoint(anchor, Direction.WEST, radius);

        BetterBlockPos[] northSouth = north != null && south != null ? new BetterBlockPos[]{north, south} : null;
        BetterBlockPos[] eastWest = east != null && west != null ? new BetterBlockPos[]{east, west} : null;

        if (northSouth != null && eastWest != null) {
            double northSouthDistance = north.distanceSq(south);
            double eastWestDistance = east.distanceSq(west);
            return northSouthDistance >= eastWestDistance ? northSouth : eastWest;
        }
        if (northSouth != null) {
            return northSouth;
        }
        if (eastWest != null) {
            return eastWest;
        }

        BetterBlockPos first = null;
        BetterBlockPos second = null;
        for (BetterBlockPos candidate : new BetterBlockPos[]{north, south, east, west}) {
            if (candidate == null) {
                continue;
            }
            if (first == null) {
                first = candidate;
                continue;
            }
            if (second == null || candidate.distanceSq(first) > second.distanceSq(first)) {
                second = candidate;
            }
        }
        return first != null && second != null ? new BetterBlockPos[]{first, second} : null;
    }

    private BetterBlockPos findFurthestPatrolPoint(BetterBlockPos anchor, Direction direction, int radius) {
        BetterBlockPos best = null;
        for (int distance = 4; distance <= radius; distance++) {
            BetterBlockPos probe = anchor.relative(direction, distance);
            BetterBlockPos candidate = findUsablePatrolPoint(probe, direction);
            if (candidate == null) {
                continue;
            }
            if (anchor.distanceSq(candidate) > (double) radius * radius) {
                continue;
            }
            best = candidate;
        }
        return best;
    }

    private BetterBlockPos findUsablePatrolPoint(BetterBlockPos probe, Direction direction) {
        int[] lateralOffsets = {0, 1, -1, 2, -2};
        int[] verticalOffsets = {0, 1, -1, 2, -2, 3, -3};
        for (int lateral : lateralOffsets) {
            for (int vertical : verticalOffsets) {
                BetterBlockPos candidate = offsetProbe(probe, direction, lateral, vertical);
                if (isPatrolPointUsable(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private BetterBlockPos offsetProbe(BetterBlockPos probe, Direction direction, int lateral, int vertical) {
        int x = probe.x;
        int z = probe.z;
        if (direction.getAxis() == Direction.Axis.Z) {
            x += lateral;
        } else {
            z += lateral;
        }
        return new BetterBlockPos(x, probe.y + vertical, z);
    }

    private boolean isPatrolPointUsable(BetterBlockPos point) {
        return !MovementHelper.isLiquid(ctx, point)
                && !MovementHelper.isLiquid(ctx, point.above())
                && MovementHelper.canWalkOn(ctx, point.below());
    }

    private enum Phase {
        PATROLLING,
        HOLDING
    }
}
