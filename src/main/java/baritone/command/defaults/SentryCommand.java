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

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.RelativeBlockPos;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.api.process.ISentryProcess;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.MovementHelper;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class SentryCommand extends Command {

    public SentryCommand(IBaritone baritone) {
        super(baritone, "sentry");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        ISentryProcess sentry = baritone.getSentryProcess();
        if (!args.hasAny()) {
            logStatus(sentry);
            return;
        }
        String action = args.getString().toLowerCase(Locale.US);
        switch (action) {
            case "start":
                if (!args.hasAny()) {
                    if (!sentry.hasRoute()) {
                        throw new CommandInvalidStateException("Usage: sentry start <x1 y1 z1> <x2 y2 z2> or sentry auto [radius]");
                    }
                    sentry.resume();
                    logDirect(String.format(
                            Locale.US,
                            "Sentry resumed between %s and %s",
                            formatPos(sentry.getFirstPoint()),
                            formatPos(sentry.getSecondPoint())
                    ));
                    return;
                }
                args.requireMax(6);
                BetterBlockPos origin = ctx.playerFeet();
                BetterBlockPos first = args.getDatatypePost(RelativeBlockPos.INSTANCE, origin);
                BetterBlockPos second = args.getDatatypePost(RelativeBlockPos.INSTANCE, origin);
                validatePatrolPoint(first);
                validatePatrolPoint(second);
                sentry.start(first, second);
                logDirect(String.format(
                        Locale.US,
                        "Sentry patrolling between %s and %s",
                        formatPos(first),
                        formatPos(second)
                ));
                return;
            case "auto":
                args.requireMax(1);
                int radius = args.hasAny()
                        ? args.getAs(Integer.class)
                        : BaritoneAPI.getSettings().sentryAutoRadius.value;
                BetterBlockPos anchor = ctx.playerFeet();
                validatePatrolPoint(anchor);
                if (!sentry.startAuto(anchor, radius)) {
                    throw new CommandInvalidStateException("Sentry could not find two dry patrol points in that radius");
                }
                logDirect(String.format(
                        Locale.US,
                        "Sentry auto patrol active around %s radius %d using %s -> %s",
                        formatPos(anchor),
                        radius,
                        formatPos(sentry.getFirstPoint()),
                        formatPos(sentry.getSecondPoint())
                ));
                return;
            case "stop":
                args.requireMax(0);
                if (sentry.isRunning()) {
                    sentry.stop();
                    logDirect("Sentry stopped");
                } else {
                    logDirect("Sentry is not running");
                }
                return;
            case "status":
                args.requireMax(0);
                logStatus(sentry);
                return;
            case "report":
                args.requireMax(1);
                Settings.SentryReportMode mode = args.getEnum(Settings.SentryReportMode.class);
                BaritoneAPI.getSettings().sentryReportMode.value = mode;
                logDirect("Sentry report mode set to " + mode.name().toLowerCase(Locale.US));
                return;
            case "sword":
            case "swords":
                args.requireMax(0);
                BaritoneAPI.getSettings().sentryPreferSword.value = true;
                logDirect("Sentry now prefers swords");
                return;
            case "axe":
            case "axes":
                args.requireMax(0);
                BaritoneAPI.getSettings().sentryPreferSword.value = false;
                logDirect("Sentry now prefers axes");
                return;
            default:
                throw new CommandInvalidStateException("Usage: sentry <start|auto|stop|status|report|sword|axe>");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append("start", "auto", "stop", "status", "report", "sword", "axe")
                    .filterPrefix(args.getString())
                    .stream();
        }
        String action = args.peekString().toLowerCase(Locale.US);
        if ("report".equals(action) && args.hasExactly(2)) {
            args.get();
            return new TabCompleteHelper()
                    .append(Settings.SentryReportMode.class)
                    .filterPrefix(args.getString())
                    .stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Patrol back and forth between two sentry points";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The sentry command starts a back-and-forth patrol between two dry patrol points.",
                "At each end, Baritone pauses, keeps a weapon drawn, scans left to right, then continues.",
                "",
                "Usage:",
                "> sentry - Show current sentry status",
                "> sentry start <x1 y1 z1> <x2 y2 z2> - Start or replace the patrol route",
                "> sentry auto [radius] - Pick a simple dry patrol route around your current position",
                "> sentry start - Resume the most recent sentry route",
                "> sentry stop - Stop sentry mode",
                "> sentry status - Show the current sentry route and phase",
                "> sentry report <off|log|chat> - Choose how sentry reports contacts",
                "> sentry sword - Prefer swords while guarding",
                "> sentry axe - Prefer axes while guarding",
                "",
                "Fine tuning is available through regular Baritone settings such as sentryHoldTicks, sentryScanAngle, sentryScanSteps, and sentryArrivalRadius."
        );
    }

    private void validatePatrolPoint(BetterBlockPos point) throws CommandInvalidStateException {
        if (MovementHelper.isLiquid(ctx, point) || MovementHelper.isLiquid(ctx, point.above())) {
            throw new CommandInvalidStateException("Sentry patrol points must not be in water or other liquids");
        }
        if (!MovementHelper.canWalkOn(ctx, point.below())) {
            throw new CommandInvalidStateException("Sentry patrol points must stand on solid ground");
        }
    }

    private void logStatus(ISentryProcess sentry) {
        logDirect(String.format(
                Locale.US,
                "Sentry is %s. Mode %s. Route %s -> %s. Target %s. Phase %s. Report %s. Weapon %s",
                sentry.isRunning() ? "running" : "stopped",
                sentry.isAutoMode()
                        ? String.format(Locale.US, "auto@%s/r=%d", formatPos(sentry.getAutoAnchor()), sentry.getAutoRadius())
                        : "manual",
                formatPos(sentry.getFirstPoint()),
                formatPos(sentry.getSecondPoint()),
                formatPos(sentry.getCurrentTarget()),
                sentry.isHoldingPosition() ? "holding" : "patrolling",
                BaritoneAPI.getSettings().sentryReportMode.value.name().toLowerCase(Locale.US),
                BaritoneAPI.getSettings().sentryPreferSword.value ? "sword" : "axe"
        ));
    }

    private String formatPos(BetterBlockPos pos) {
        return pos == null
                ? "(unset)"
                : String.format(Locale.US, "(%d, %d, %d)", pos.x, pos.y, pos.z);
    }
}
