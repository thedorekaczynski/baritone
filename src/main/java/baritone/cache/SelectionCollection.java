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

package baritone.cache;

import baritone.api.selection.ISelection;
import baritone.api.utils.BetterBlockPos;
import baritone.selection.Selection;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Per-world persistent named selections.
 */
public class SelectionCollection {

    private static final long SELECTION_MAGIC_VALUE = 826265443329L;

    private final Path file;
    private final Map<String, SavedSelection> selections;

    SelectionCollection(Path file) {
        this.file = file;
        this.selections = new LinkedHashMap<>();
        load();
    }

    private synchronized void load() {
        this.selections.clear();
        if (!Files.exists(this.file)) {
            return;
        }

        try (
                FileInputStream fileIn = new FileInputStream(this.file.toFile());
                BufferedInputStream bufIn = new BufferedInputStream(fileIn);
                DataInputStream in = new DataInputStream(bufIn)
        ) {
            long magic = in.readLong();
            if (magic != SELECTION_MAGIC_VALUE) {
                throw new IOException("Bad magic value " + magic);
            }

            int count = in.readInt();
            while (count-- > 0) {
                String name = in.readUTF();
                int selectionCount = in.readInt();
                if (selectionCount <= 0) {
                    continue;
                }
                ISelection[] loaded = new ISelection[selectionCount];
                for (int i = 0; i < selectionCount; i++) {
                    BetterBlockPos pos1 = new BetterBlockPos(in.readInt(), in.readInt(), in.readInt());
                    BetterBlockPos pos2 = new BetterBlockPos(in.readInt(), in.readInt(), in.readInt());
                    loaded[i] = new Selection(pos1, pos2);
                }
                this.selections.put(normalize(name), new SavedSelection(name, loaded));
            }
        } catch (IOException ex) {
            System.out.println("Failed to load saved selections from " + this.file + ": " + ex.getMessage());
            this.selections.clear();
        }
    }

    private synchronized void save() {
        try {
            Path parent = this.file.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
        } catch (IOException ignored) {}

        try (
                FileOutputStream fileOut = new FileOutputStream(this.file.toFile());
                BufferedOutputStream bufOut = new BufferedOutputStream(fileOut);
                DataOutputStream out = new DataOutputStream(bufOut)
        ) {
            out.writeLong(SELECTION_MAGIC_VALUE);
            out.writeInt(this.selections.size());
            for (SavedSelection saved : this.selections.values()) {
                out.writeUTF(saved.name);
                out.writeInt(saved.selections.length);
                for (ISelection selection : saved.selections) {
                    out.writeInt(selection.pos1().x);
                    out.writeInt(selection.pos1().y);
                    out.writeInt(selection.pos1().z);
                    out.writeInt(selection.pos2().x);
                    out.writeInt(selection.pos2().y);
                    out.writeInt(selection.pos2().z);
                }
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public synchronized Set<String> getNames() {
        Set<String> names = new LinkedHashSet<>();
        for (SavedSelection selection : this.selections.values()) {
            names.add(selection.name);
        }
        return Collections.unmodifiableSet(names);
    }

    public synchronized ISelection[] getSelection(String name) {
        SavedSelection saved = this.selections.get(normalize(name));
        return saved == null ? null : saved.copySelections();
    }

    public synchronized void putSelection(String name, ISelection[] savedSelections) {
        this.selections.put(normalize(name), new SavedSelection(name, savedSelections));
        save();
    }

    public synchronized boolean remove(String name) {
        SavedSelection removed = this.selections.remove(normalize(name));
        if (removed != null) {
            save();
            return true;
        }
        return false;
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.US);
    }

    private static final class SavedSelection {

        private final String name;
        private final ISelection[] selections;

        private SavedSelection(String name, ISelection[] selections) {
            this.name = name;
            this.selections = Arrays.copyOf(selections, selections.length);
        }

        private ISelection[] copySelections() {
            return Arrays.copyOf(this.selections, this.selections.length);
        }
    }
}
