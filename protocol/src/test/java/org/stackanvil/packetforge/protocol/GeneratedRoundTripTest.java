package org.stackanvil.packetforge.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.InputStreamReader;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.stackanvil.packetforge.commons.*;

class GeneratedRoundTripTest {
    @Test
    void everyGeneratedRegistrationRoundTripsBodiesAndNumericIds() throws Exception {
        Map<String, JsonObject> models = models();
        PacketRegistry registry = GeneratedProtocol.registry();
        for (var entry : registry.entries().values())
            for (int variant = 0; variant < 3; variant++) {
                Packet packet = (Packet) sample(entry.type(), "", variant, models);
                ByteBuf buffer = Unpooled.buffer();
                ByteBuf reencoded = Unpooled.buffer();
                try {
                    registry.encode(entry.key(), packet, buffer);
                    byte[] expected = new byte[buffer.readableBytes()];
                    buffer.getBytes(0, expected);
                    Packet decoded =
                            registry.decode(entry.key().state(), entry.key().flow(), buffer);
                    registry.encode(entry.key(), decoded, reencoded);
                    byte[] actual = new byte[reencoded.readableBytes()];
                    reencoded.readBytes(actual);
                    assertArrayEquals(expected, actual, entry.name() + " variant " + variant);
                } finally {
                    buffer.release();
                    reencoded.release();
                }
            }
    }

    @Test
    void retainsStandaloneRecordValidationGuards() {
        var codec = org.stackanvil.packetforge.protocol.type.world.item.enchantment.Enchantable.CODEC;
        ByteBuf invalid = Unpooled.wrappedBuffer(new byte[] {0});
        try {
            assertThrows(IllegalArgumentException.class, () -> codec.decode(invalid));
        } finally {
            invalid.release();
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new org.stackanvil.packetforge.protocol.type.world.item.enchantment.Enchantable(0));
    }

    static Map<String, JsonObject> models() throws Exception {
        Map<String, JsonObject> models = new HashMap<>();
        try (var stream = GeneratedProtocol.class.getResourceAsStream("/packetforge/protocol.json")) {
            assertNotNull(stream);
            try (var reader = new InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)) {
                var report = JsonParser.parseReader(reader).getAsJsonObject();
                assertEquals(260, report.get("totalRegistrations").getAsInt());
                assertEquals(260, report.get("generatedRegistrations").getAsInt());
                assertEquals(0, report.getAsJsonObject("registryGaps").size(), "Unresolved registry payloads");
                for (var model : report.getAsJsonArray("models")) {
                    var object = model.getAsJsonObject();
                    models.put(object.get("generatedName").getAsString(), object);
                }
                for (var dispatch : report.getAsJsonArray("dispatches")) {
                    var object = dispatch.getAsJsonObject();
                    models.put("$dispatch:" + object.get("name").getAsString(), object);
                }
            }
        }
        return models;
    }

    static Type type(String name) throws ClassNotFoundException {
        name = name.strip();
        if (name.contains("<")) {
            Class<?> raw = (Class<?>) type(name.substring(0, name.indexOf('<')));
            var names = innerShapes(name);
            Type[] parameters = new Type[names.size()];
            for (int i = 0; i < names.size(); i++) parameters[i] = type(names.get(i));
            return new ParameterizedType() {
                public Type[] getActualTypeArguments() {
                    return parameters.clone();
                }

                public Type getRawType() {
                    return raw;
                }

                public Type getOwnerType() {
                    return null;
                }
            };
        }
        if (name.endsWith("[]"))
            return java.lang.reflect.Array.newInstance((Class<?>) type(name.substring(0, name.length() - 2)), 0)
                    .getClass();
        return switch (name) {
            case "boolean" -> boolean.class;
            case "byte" -> byte.class;
            case "short" -> short.class;
            case "int" -> int.class;
            case "long" -> long.class;
            case "float" -> float.class;
            case "double" -> double.class;
            case "String", "Boolean", "Byte", "Short", "Integer", "Long", "Float", "Double" ->
                Class.forName("java.lang." + name);
            default -> Class.forName(name);
        };
    }

    static Object dispatchKey(Type type, String name) {
        if (type instanceof ParameterizedType generic && generic.getRawType() == Either.class) {
            int id = Integer.parseInt(name.substring(name.indexOf(':') + 1));
            return name.startsWith("left:") ? Either.left(id) : Either.right(id);
        }
        Class<?> clazz = (Class<?>) type;
        if (clazz == String.class) return name;
        return clazz.isEnum()
                ? java.util.Arrays.stream(clazz.getEnumConstants())
                        .filter(v -> ((Enum<?>) v).name().equals(name))
                        .findFirst()
                        .orElseThrow()
                : Integer.parseInt(name);
    }

    private static final class Keys {
        static final java.security.PublicKey PUBLIC;

        static {
            try {
                var generator = java.security.KeyPairGenerator.getInstance("RSA");
                generator.initialize(1024);
                PUBLIC = generator.generateKeyPair().getPublic();
            } catch (java.security.GeneralSecurityException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    static List<String> innerShapes(String shape) {
        if (!shape.contains("<")) return List.of();
        String content = shape.substring(shape.indexOf('<') + 1, shape.length() - 1);
        var result = new java.util.ArrayList<String>();
        int depth = 0, start = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                result.add(content.substring(start, i));
                start = i + 1;
            }
        }
        result.add(content.substring(start));
        return result;
    }

    private static final ThreadLocal<java.util.List<String>> SAMPLE_TYPES =
            ThreadLocal.withInitial(java.util.ArrayList::new);

    static Object sample(Type type, String shape, int variant, Map<String, JsonObject> models) throws Exception {
        var path = SAMPLE_TYPES.get();
        String identity = type + "#" + shape;
        int boundedVariant = path.contains(identity) ? 0 : variant;
        path.add(identity);
        try {
            return sampleValue(type, shape, boundedVariant, models);
        } finally {
            path.removeLast();
        }
    }

    private static Object sampleValue(Type type, String shape, int variant, Map<String, JsonObject> models)
            throws Exception {
        if (shape.startsWith("length(")) shape = shape.substring(shape.indexOf('<') + 1, shape.length() - 1);
        if (shape.startsWith("nullable<")) {
            if (variant == 0) return null;
            shape = shape.substring("nullable<".length(), shape.length() - 1);
        }
        if (type instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            List<String> innerShapes = innerShapes(shape);
            String inner = innerShapes.isEmpty() ? "" : innerShapes.getFirst();
            if (parameterized.getRawType() == Optional.class)
                return variant == 0 ? Optional.empty() : Optional.of(sample(arguments[0], inner, variant, models));
            if (parameterized.getRawType() == List.class) {
                int count = shape.startsWith("fixedList(")
                        ? Integer.parseInt(shape.substring(10, shape.indexOf(')')))
                        : variant == 0 ? 0 : 1;
                var values = new java.util.ArrayList<>();
                for (int i = 0; i < count; i++) values.add(sample(arguments[0], inner, variant, models));
                return values;
            }
            if (parameterized.getRawType() == Set.class)
                return variant == 0 ? Set.of() : Set.of(sample(arguments[0], inner, variant, models));
            if (parameterized.getRawType() == Map.class)
                return variant == 0
                        ? Map.of()
                        : Map.of(
                                sample(arguments[0], inner, variant, models),
                                sample(arguments[1], innerShapes.get(1), variant, models));
            if (parameterized.getRawType() == Variant.class) {
                var dispatch = models.get("$" + shape);
                if (dispatch == null) throw new IllegalArgumentException("Missing dispatch metadata: " + shape);
                var cases = new java.util.ArrayList<com.google.gson.JsonObject>();
                for (var candidate : dispatch.getAsJsonArray("cases"))
                    if (!candidate
                            .getAsJsonObject()
                            .getAsJsonObject("value")
                            .get("shape")
                            .getAsString()
                            .startsWith("REJECTED:")) cases.add(candidate.getAsJsonObject());
                var branch = cases.get(variant % cases.size());
                String keyName = branch.get("key").getAsString();
                Object key = dispatchKey(arguments[0], keyName);
                var value = branch.getAsJsonObject("value");
                return new Variant<>(
                        key,
                        sample(
                                type(value.get("type").getAsString()),
                                value.get("shape").getAsString(),
                                variant,
                                models));
            }
            if (parameterized.getRawType() == RegistryValue.class)
                return variant == 1
                        ? new RegistryValue.Direct<>(sample(arguments[0], inner, variant, models))
                        : new RegistryValue.Reference<>(0);
            if (parameterized.getRawType() == Either.class)
                return variant % 2 == 0
                        ? Either.left(sample(arguments[0], inner, variant, models))
                        : Either.right(sample(arguments[1], innerShapes.get(1), variant, models));
            throw new IllegalArgumentException("Unrecognized generic type: " + type);
        }
        Class<?> clazz = (Class<?>) type;
        if (clazz == MetadataEntry.class) {
            @SuppressWarnings("unchecked")
            Variant<Integer> value = (Variant<Integer>)
                    sample(type("org.stackanvil.packetforge.commons.Variant<Integer>"), shape, variant, models);
            return new MetadataEntry(variant, value);
        }
        if (clazz == HolderSet.class) return new HolderSet.Direct(variant == 0 ? List.of() : List.of(1));
        if (clazz == ComponentPatch.class)
            return variant == 0
                    ? ComponentPatch.EMPTY
                    : ComponentPatch.of(DataComponentTypes.MAX_STACK_SIZE.withValue(12 + variant));
        if (clazz == ItemStack.class)
            return variant == 0 && shape.contains("OPTIONAL_")
                    ? ItemStack.EMPTY
                    : new ItemStack(1 + variant, 1, (ComponentPatch) sample(ComponentPatch.class, "", variant, models));
        if (clazz == ProfileProperty.class)
            return new ProfileProperty("property" + variant, "sample" + variant, variant == 0 ? null : "signature");
        if (clazz == java.security.PublicKey.class) return Keys.PUBLIC;
        if (clazz == NbtTag.class && shape.contains("TypeBase#CustomDataPredicate.CODEC"))
            return new NbtTag.StringValue("{}");
        if (clazz == NbtTag.class && shape.contains("TypeBase#"))
            return shape.contains("EnchantmentsPredicate.") || shape.endsWith("VillagerTypePredicate.CODEC")
                    ? new NbtTag.ListValue((byte) 0, List.of())
                    : new NbtTag.Compound(Map.of());
        if (clazz == NbtTag.class && shape.contains("DataComponents#")) {
            if (shape.endsWith("Recipe.KEY_CODEC.listOf()"))
                return new NbtTag.ListValue((byte) 8, List.of(new NbtTag.StringValue("minecraft:sample")));
            if (shape.endsWith("SeededContainerLoot.CODEC"))
                return new NbtTag.Compound(Map.of("loot_table", new NbtTag.StringValue("minecraft:empty")));
            return new NbtTag.Compound(Map.of());
        }
        if (clazz == net.kyori.adventure.text.Component.class)
            return net.kyori.adventure.text.Component.text(variant == 0 ? "" : "sample" + variant);
        if (clazz == net.kyori.adventure.text.format.Style.class)
            return variant == 0
                    ? net.kyori.adventure.text.format.Style.empty()
                    : net.kyori.adventure.text.format.Style.style(net.kyori.adventure.text.format.TextDecoration.BOLD);
        if (clazz == NbtTag.class && shape.contains("net.minecraft.server.dialog.Dialog#"))
            return new NbtTag.Compound(new java.util.HashMap<>(Map.of(
                    "type",
                    new NbtTag.StringValue("minecraft:notice"),
                    "title",
                    new NbtTag.StringValue("sample" + variant))));
        if (clazz == NbtTag.class) return new NbtTag.StringValue(variant == 0 ? "" : "sample" + variant);
        if (clazz == NbtTag.Compound.class)
            return shape.equals("NBT_COMPOUND_NULLABLE") && variant == 0
                    ? null
                    : new NbtTag.Compound(Map.of("sample", new NbtTag.IntValue(variant)));
        if (clazz == com.google.gson.JsonElement.class)
            return shape.contains("ServerStatus")
                    ? com.google.gson.JsonParser.parseString("{\"description\":\"sample" + variant + "\"}")
                    : new com.google.gson.JsonPrimitive("sample" + variant);
        if (clazz == String.class) return shape.equals("IDENTIFIER") ? "minecraft:stone" : variant == 0 ? "" : "é";
        if (clazz == int.class || clazz == Integer.class) return shape.startsWith("REGISTRY:") ? 0 : variant;
        if (clazz == long.class || clazz == Long.class) return variant == 2 ? Long.MIN_VALUE : (long) variant;
        if (clazz == float.class || clazz == Float.class) return (float) variant;
        if (clazz == double.class || clazz == Double.class) return (double) variant;
        if (clazz == byte.class || clazz == Byte.class) return (byte) variant;
        if (clazz == short.class || clazz == Short.class) return (short) variant;
        if (clazz == boolean.class || clazz == Boolean.class) return variant != 0;
        if (clazz == byte[].class) return variant == 0 ? new byte[0] : new byte[] {1, -1};
        if (clazz == int[].class) return variant == 0 ? new int[0] : new int[] {variant, -variant};
        if (clazz == long[].class) return variant == 0 ? new long[0] : new long[] {variant, Long.MIN_VALUE};
        if (clazz == OptionalInt.class) return variant == 0 ? OptionalInt.empty() : OptionalInt.of(variant - 1);
        if (clazz == Instant.class) return Instant.ofEpochMilli(variant);
        if (clazz == BitSet.class) {
            BitSet value = new BitSet();
            if (variant != 0)
                value.set(
                        shape.startsWith("FIXED_BITS(")
                                ? variant % Integer.parseInt(shape.substring(11, shape.length() - 1))
                                : variant * 65);
            return value;
        }
        if (clazz == Vector3.class) return new Vector3(variant, -variant, variant * 2);
        if (clazz == FloatVector3.class) return new FloatVector3(variant, -variant, variant * 2);
        if (clazz == Quaternion.class) return new Quaternion(variant, -variant, variant, 1);
        if (clazz == ChunkPosition.class) return new ChunkPosition(-variant, variant);
        if (clazz == CommandNode.class) return new CommandNode(0, new int[0], 0, null);
        if (clazz == UUID.class) return new UUID(variant, -variant);
        if (clazz == BlockPosition.class) return new BlockPosition(-variant, variant, variant);
        if (clazz.isEnum()) {
            Object[] values = clazz.getEnumConstants();
            return values[variant % values.length];
        }
        if (clazz.isRecord()) {
            JsonObject model = models.get(clazz.getName());
            String originalName = model.get("originalName").getAsString();
            boolean conditionalFixture =
                    originalName.startsWith("net.minecraft.network.protocol.game.ClientboundBossEventPacket.")
                            || originalName.equals("net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket")
                            || originalName.equals("net.minecraft.network.protocol.game.ClientboundSetObjectivePacket")
                            || originalName.equals("net.minecraft.advancements.DisplayInfo");
            if (model.has("program") && !conditionalFixture) {
                @SuppressWarnings("unchecked")
                PacketCodec<Object> codec =
                        (PacketCodec<Object>) clazz.getField("CODEC").get(null);
                RuntimeException failure = null;
                for (int seed : new int[] {variant, variant % 2, 0}) {
                    byte[] bytes = new byte[16384];
                    bytes[0] = (byte) seed;
                    var input = Unpooled.wrappedBuffer(bytes);
                    try {
                        return codec.decode(input);
                    } catch (RuntimeException e) {
                        failure = e;
                    } finally {
                        input.release();
                    }
                }
                throw failure;
            }
            var fields = model.getAsJsonArray("fields");
            var components = clazz.getRecordComponents();
            Class<?>[] parameters = new Class<?>[components.length];
            Object[] values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                parameters[i] = components[i].getType();
                String original = model.get("originalName").getAsString();
                boolean nonempty = original.equals("net.minecraft.world.level.pathfinder.Path.DebugData")
                                && components[i].getName().equals("targetNodes")
                        || original.equals("net.minecraft.world.entity.PositionPath.Stepped")
                                && components[i].getName().equals("steps");
                int fieldVariant = original.equals("net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket")
                                && components[i].getName().equals("parameters")
                        ? variant == 1 ? 0 : variant + 1
                        : original.equals("net.minecraft.world.entity.npc.villager.VillagerData")
                                        && components[i].getName().equals("level")
                                ? variant + 1
                                : original.equals("net.minecraft.world.level.block.entity.SignText")
                                                && components[i].getName().equals("filteredMessagesForSerialization")
                                                && variant != 0
                                        ? variant + 1
                                        : original.equals("net.minecraft.world.item.enchantment.Enchantable")
                                                ? variant + 1
                                                : original.startsWith("net.minecraft.core.particles.")
                                                                && components[i]
                                                                        .getName()
                                                                        .equals("scale")
                                                        ? variant + 1
                                                        : nonempty ? Math.max(1, variant) : variant;
                values[i] = original.equals("net.minecraft.network.protocol.login.ClientboundCustomQueryPacket")
                                && components[i].getName().equals("data")
                        ? new byte[0]
                        : original.equals("net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket")
                                        && components[i].getName().equals("data")
                                ? new byte[] {1}
                                : original.equals("net.minecraft.world.item.trading.MerchantOffer")
                                                && components[i].getName().equals("maxUses")
                                        ? 100
                                        : original.equals("net.minecraft.world.item.trading.MerchantOffer")
                                                        && components[i]
                                                                .getName()
                                                                .equals("uses")
                                                ? variant == 2 ? 100 : variant
                                                : original.equals("net.minecraft.world.item.trading.MerchantOffer")
                                                                && components[i]
                                                                        .getName()
                                                                        .equals("isExhausted")
                                                        ? variant == 2
                                                        : original.equals("net.minecraft.world.item.trading.ItemCost")
                                                                        && components[i]
                                                                                .getName()
                                                                                .equals("item")
                                                                ? 1
                                                                : original.equals(
                                                                                        "net.minecraft.world.item.trading.ItemCost")
                                                                                && components[i]
                                                                                        .getName()
                                                                                        .equals("count")
                                                                        ? variant + 1
                                                                        : original.equals(
                                                                                                "net.minecraft.world.effect.MobEffectInstance.Details")
                                                                                        && components[i]
                                                                                                .getName()
                                                                                                .equals("hiddenEffect")
                                                                                        && SAMPLE_TYPES.get().stream()
                                                                                                .anyMatch(
                                                                                                        t -> t.contains(
                                                                                                                ".PotionContents#"))
                                                                                ? Optional.empty()
                                                                                : original.equals(
                                                                                                        "net.minecraft.world.item.ItemStackTemplate")
                                                                                                && components[i]
                                                                                                        .getName()
                                                                                                        .equals("item")
                                                                                        ? 1
                                                                                        : original.equals(
                                                                                                                "net.minecraft.world.item.ItemStackTemplate")
                                                                                                        && components[i]
                                                                                                                .getName()
                                                                                                                .equals(
                                                                                                                        "count")
                                                                                                ? variant + 1
                                                                                                : sample(
                                                                                                        components[i]
                                                                                                                .getGenericType(),
                                                                                                        fields.get(i)
                                                                                                                .getAsJsonObject()
                                                                                                                .getAsJsonObject(
                                                                                                                        "read")
                                                                                                                .get(
                                                                                                                        "shape")
                                                                                                                .getAsString(),
                                                                                                        fieldVariant,
                                                                                                        models);
            }
            if (originalName.equals("net.minecraft.world.level.saveddata.maps.MapItemSavedData.MapPatch#sentinel"))
                values[4] = new byte[(int) values[0] * (int) values[1]];
            if (model.get("originalName")
                    .getAsString()
                    .equals("net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay")) {
                values[0] = variant + 1;
                values[1] = 1;
                @SuppressWarnings("unchecked")
                List<Object> ingredients = (List<Object>) values[2];
                Object ingredient = ingredients.isEmpty()
                        ? sample(
                                ((ParameterizedType) components[2].getGenericType()).getActualTypeArguments()[0],
                                innerShapes(fields.get(2)
                                                .getAsJsonObject()
                                                .getAsJsonObject("read")
                                                .get("shape")
                                                .getAsString())
                                        .getFirst(),
                                0,
                                models)
                        : ingredients.getFirst();
                values[2] = java.util.Collections.nCopies(variant + 1, ingredient);
            }
            return clazz.getConstructor(parameters).newInstance(values);
        }
        throw new IllegalArgumentException("No sample for " + clazz);
    }
}
