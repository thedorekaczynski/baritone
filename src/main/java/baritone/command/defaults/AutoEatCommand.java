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
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class AutoEatCommand extends Command {

    public AutoEatCommand(IBaritone baritone) {
        super(baritone, "autoeat", "ae");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(1);
        if (!args.hasAny()) {
            boolean enabled = !Baritone.settings().autoEat.value;
            Baritone.settings().autoEat.value = enabled;
            logDirect("Auto eat " + (enabled ? "enabled" : "disabled"));
            return;
        }
        String subcommand = args.getString().toLowerCase(Locale.US);
        switch (subcommand) {
            case "on":
            case "start":
                Baritone.settings().autoEat.value = true;
                logDirect("Auto eat enabled");
                break;
            case "off":
            case "stop":
                Baritone.settings().autoEat.value = false;
                logDirect("Auto eat disabled");
                break;
            case "status":
                logDirect(String.format(
                        Locale.US,
                        "Auto eat is %s. Hunger threshold %d, using hotbar food only while a Baritone operation is running",
                        Baritone.settings().autoEat.value ? "enabled" : "disabled",
                        Baritone.settings().autoEatHungerThreshold.value
                ));
                break;
            default:
                throw new CommandInvalidStateException("Usage: autoeat [on|off|status]");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            String prefix = args.getString().toLowerCase(Locale.US);
            return Stream.of("on", "off", "status").filter(option -> option.startsWith(prefix));
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Pause pathing to eat hotbar food at low hunger";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The autoeat command toggles a temporary pathing pause for eating.",
                "When enabled, Baritone pauses only while a Baritone operation is already running, swaps to hotbar food, eats at 5 hunger, then resumes.",
                "It does not pull food from main inventory slots.",
                "",
                "Usage:",
                "> autoeat - toggle auto eat.",
                "> autoeat on - enable auto eat.",
                "> autoeat off - disable auto eat.",
                "> autoeat status - show current settings."
        );
    }
}
