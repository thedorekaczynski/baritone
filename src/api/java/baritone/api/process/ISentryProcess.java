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

package baritone.api.process;

import baritone.api.utils.BetterBlockPos;

public interface ISentryProcess extends IBaritoneProcess {

    void start(BetterBlockPos first, BetterBlockPos second);

    boolean startAuto(BetterBlockPos anchor, int radius);

    void resume();

    void stop();

    boolean isRunning();

    BetterBlockPos getFirstPoint();

    BetterBlockPos getSecondPoint();

    BetterBlockPos getCurrentTarget();

    BetterBlockPos getAutoAnchor();

    int getAutoRadius();

    boolean isAutoMode();

    boolean isHoldingPosition();

    default boolean hasRoute() {
        return getFirstPoint() != null && getSecondPoint() != null;
    }
}
