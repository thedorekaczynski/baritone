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

package baritone.api.cache;

import baritone.api.selection.ISelection;

import java.util.Set;

/**
 * @author Brady
 * @since 9/24/2018
 */
public interface IWorldData {

    /**
     * Returns the cached world for this world. A cached world is a simplified format
     * of a regular world, intended for use on multiplayer servers where chunks are not
     * traditionally stored to disk, allowing for long distance pathing with minimal disk usage.
     *
     * @return The cached world for this world
     */
    ICachedWorld getCachedWorld();

    /**
     * @return The waypoint collection for this world
     */
    IWaypointCollection getWaypoints();

    /**
     * @return The names of all saved selections for this world
     */
    Set<String> getSavedSelectionNames();

    /**
     * @param name The saved selection name
     * @return A copy of the saved selections for that name, or {@code null} if none exist
     */
    ISelection[] getSavedSelection(String name);

    /**
     * Saves the given selections under the specified name, replacing any existing saved selection of that name.
     *
     * @param name       The saved selection name
     * @param selections The selections to save
     */
    void saveSelection(String name, ISelection[] selections);

    /**
     * Removes a saved selection by name.
     *
     * @param name The saved selection name
     * @return {@code true} if a saved selection was removed
     */
    boolean removeSavedSelection(String name);

}
