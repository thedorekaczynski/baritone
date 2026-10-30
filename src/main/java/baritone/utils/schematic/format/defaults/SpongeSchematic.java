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

package baritone.utils.schematic.format.defaults;

import baritone.utils.schematic.StaticSchematic;
import baritone.utils.type.VarInt;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Brady
 * @since 12/27/2019
 */
public final class SpongeSchematic extends StaticSchematic {

    public SpongeSchematic(CompoundTag nbt) {
        this.x = nbt.getInt("Width").orElse(0);
        this.y = nbt.getInt("Height").orElse(0);
        this.z = nbt.getInt("Length").orElse(0);
        this.states = new BlockState[this.x][this.z][this.y];

        Int2ObjectArrayMap<BlockState> palette = new Int2ObjectArrayMap<>();
        CompoundTag paletteTag = nbt.getCompound("Palette").orElse(new CompoundTag());
        for (String tag : paletteTag.keySet()) {
            int index = paletteTag.getInt(tag).orElse(0);

            SerializedBlockState serializedState = SerializedBlockState.getFromString(tag);
            if (serializedState == null) {
                throw new IllegalArgumentException("Unable to parse palette tag");
            }

            BlockState state = serializedState.deserialize();
            if (state == null) {
                throw new IllegalArgumentException("Unable to deserialize palette tag");
            }

            palette.put(index, state);
        }

        // BlockData is stored as an NBT byte[], however, the actual data that is represented is a varint[]
        byte[] rawBlockData = nbt.getByteArray("BlockData").orElseThrow();
        int[] blockData = new int[this.x * this.y * this.z];
        int offset = 0;
        for (int i = 0; i < blockData.length; i++) {
            if (offset >= rawBlockData.length) {
                throw new IllegalArgumentException("No remaining bytes in BlockData for complete schematic");
            }

            VarInt varInt = VarInt.read(rawBlockData, offset);
            blockData[i] = varInt.getValue();
            offset += varInt.getSize();
        }

        for (int y = 0; y < this.y; y++) {
            for (int z = 0; z < this.z; z++) {
                for (int x = 0; x < this.x; x++) {
                    int index = (y * this.z + z) * this.x + x;
                    BlockState state = palette.get(blockData[index]);
                    if (state == null) {
                        throw new IllegalArgumentException("Invalid Palette Index " + index);
                    }

                    this.states[x][z][y] = state;
                }
            }
        }
    }

    public static void write(StaticSchematic schematic, File file, String name) throws IOException {
        try (OutputStream output = java.nio.file.Files.newOutputStream(file.toPath())) {
            NbtIo.writeCompressed(serialize(schematic, name), output);
        }
    }

    public static CompoundTag serialize(StaticSchematic schematic, String name) {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 2);
        root.putInt("Width", schematic.widthX());
        root.putInt("Height", schematic.heightY());
        root.putInt("Length", schematic.lengthZ());

        CompoundTag metadata = new CompoundTag();
        metadata.putString("Name", name);
        metadata.putString("Author", "Baritone");
        root.put("Metadata", metadata);

        LinkedHashMap<String, Integer> palette = new LinkedHashMap<>();
        ByteArrayOutputStream blockData = new ByteArrayOutputStream();
        for (int y = 0; y < schematic.heightY(); y++) {
            for (int z = 0; z < schematic.lengthZ(); z++) {
                for (int x = 0; x < schematic.widthX(); x++) {
                    String serialized = SerializedBlockState.toString(schematic.getDirect(x, y, z));
                    int index = palette.computeIfAbsent(serialized, ignored -> palette.size());
                    byte[] encoded = new VarInt(index).serialize();
                    blockData.write(encoded, 0, encoded.length);
                }
            }
        }

        CompoundTag paletteTag = new CompoundTag();
        for (Map.Entry<String, Integer> entry : palette.entrySet()) {
            paletteTag.putInt(entry.getKey(), entry.getValue());
        }
        root.put("Palette", paletteTag);
        root.putInt("PaletteMax", palette.size());
        root.putByteArray("BlockData", blockData.toByteArray());
        return root;
    }

    private static final class SerializedBlockState {

        private static final Pattern REGEX = Pattern.compile("(?<location>(\\w+:)?\\w+)(\\[(?<properties>(\\w+=\\w+,?)+)])?");

        private final Identifier identifier;
        private final Map<String, String> properties;
        private BlockState blockState;

        private SerializedBlockState(Identifier identifier, Map<String, String> properties) {
            this.identifier = identifier;
            this.properties = properties;
        }

        private BlockState deserialize() {
            if (this.blockState == null) {
                Block block = BuiltInRegistries.BLOCK.get(this.identifier)
                    .map(Holder.Reference::value)
                    .orElse(Blocks.AIR);
                this.blockState = block.defaultBlockState();

                this.properties.keySet().stream().sorted(String::compareTo).forEachOrdered(key -> {
                    Property<?> property = block.getStateDefinition().getProperty(key);
                    if (property != null) {
                        this.blockState = setPropertyValue(this.blockState, property, this.properties.get(key));
                    }
                });
            }
            return this.blockState;
        }

        private static SerializedBlockState getFromString(String s) {
            Matcher m = REGEX.matcher(s);
            if (!m.matches()) {
                return null;
            }

            try {
                String location = m.group("location");
                String properties = m.group("properties");

                Identifier identifier = Identifier.parse(location);
                Map<String, String> propertiesMap = new HashMap<>();
                if (properties != null) {
                    for (String property : properties.split(",")) {
                        String[] split = property.split("=");
                        propertiesMap.put(split[0], split[1]);
                    }
                }

                return new SerializedBlockState(identifier, propertiesMap);
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }

        private static String toString(BlockState state) {
            Identifier identifier = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            if (identifier == null) {
                identifier = Identifier.parse("minecraft:air");
            }
            Map<Property<?>, Comparable<?>> values = state.getValues().collect(java.util.stream.Collectors.toMap(Property.Value::property, Property.Value::value));
            if (values.isEmpty()) {
                return identifier.toString();
            }
            List<String> properties = values.entrySet().stream()
                    .sorted(java.util.Comparator.comparing(entry -> entry.getKey().getName()))
                    .map(entry -> entry.getKey().getName() + "=" + propertyValueToString(entry.getKey(), entry.getValue()))
                    .toList();
            return identifier + "[" + String.join(",", properties) + "]";
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private static String propertyValueToString(Property property, Comparable value) {
            return property.getName(value);
        }

        private static <T extends Comparable<T>> BlockState setPropertyValue(BlockState state, Property<T> property, String value) {
            Optional<T> parsed = property.getValue(value);
            if (parsed.isPresent()) {
                return state.setValue(property, parsed.get());
            } else {
                throw new IllegalArgumentException("Invalid value for property " + property);
            }
        }
    }
}
