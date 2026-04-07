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

public class HostileAuraCommand extends Command {

    public HostileAuraCommand(IBaritone baritone) {
        super(baritone, "killaura", "ka");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(1);
        if (!args.hasAny()) {
            boolean enabled = !Baritone.settings().killAuraHostiles.value;
            Baritone.settings().killAuraHostiles.value = enabled;
            logDirect("Hostile killaura " + (enabled ? "enabled" : "disabled"));
            return;
        }
        String subcommand = args.getString().toLowerCase(Locale.US);
        switch (subcommand) {
            case "on":
            case "start":
                Baritone.settings().killAuraHostiles.value = true;
                logDirect("Hostile killaura enabled");
                break;
            case "off":
            case "stop":
                Baritone.settings().killAuraHostiles.value = false;
                logDirect("Hostile killaura disabled");
                break;
            case "status":
                logDirect(String.format(
                        Locale.US,
                        "Hostile killaura is %s. Range %.1f, cooldown %.2f, preferring %s",
                        Baritone.settings().killAuraHostiles.value ? "enabled" : "disabled",
                        Baritone.settings().killAuraHostileRange.value,
                        Baritone.settings().killAuraAttackCooldown.value,
                        Baritone.settings().killAuraPreferSword.value ? "swords" : "axes"
                ));
                break;
            case "sword":
            case "swords":
                Baritone.settings().killAuraPreferSword.value = true;
                logDirect("Hostile killaura now prefers swords");
                break;
            case "axe":
            case "axes":
                Baritone.settings().killAuraPreferSword.value = false;
                logDirect("Hostile killaura now prefers axes");
                break;
            default:
                throw new CommandInvalidStateException("Usage: ka [on|off|status|sword|axe]");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            String prefix = args.getString().toLowerCase(Locale.US);
            return Stream.of("on", "off", "status", "sword", "axe").filter(option -> option.startsWith(prefix));
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Pause and attack nearby hostile mobs, then continue pathing";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The killaura command toggles a defensive hostile-mob combat pause.",
                "When enabled, Baritone pauses pathing for nearby hostile mobs, attacks with vanilla cooldown timing, then resumes.",
                "Creepers and magma cubes are treated as pause-only threats and will not be auto-swung at.",
                "",
                "Usage:",
                "> ka - toggle hostile killaura.",
                "> ka on - enable hostile killaura.",
                "> ka off - disable hostile killaura.",
                "> ka status - show current settings.",
                "> ka sword - prefer swords over axes.",
                "> ka axe - prefer axes over swords."
        );
    }
}
