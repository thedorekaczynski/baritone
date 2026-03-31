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

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class CowHuntCommand extends Command {

    public CowHuntCommand(IBaritone baritone) {
        super(baritone, "cowhunt", "cowkiller", "beef");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(1);
        if (!args.hasAny()) {
            baritone.getCowHuntProcess().hunt();
            return;
        }
        String subcommand = args.getString().toLowerCase(Locale.US);
        switch (subcommand) {
            case "start":
                baritone.getCowHuntProcess().hunt();
                break;
            case "status":
                logDirect(baritone.getCowHuntProcess().status());
                break;
            default:
                throw new CommandInvalidStateException("Usage: cowhunt [start|status]");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            String prefix = args.getString().toLowerCase(Locale.US);
            return Stream.of("start", "status").filter(option -> option.startsWith(prefix));
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Hunt cows while avoiding #sel protected farm areas";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The cowhunt command makes Baritone roam surface terrain, find cows, kill them, and pick up beef/leather.",
                "Any #sel area is treated as a protected farm zone, so cows inside those selections are ignored.",
                "",
                "Usage:",
                "> cowhunt - start hunting cows.",
                "> cowhunt start - start hunting cows.",
                "> cowhunt status - print the current or last run summary.",
                "> stop - stop the hunt and print a summary."
        );
    }
}
