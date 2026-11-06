package org.stackanvil.packetforge.protocol;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.stackanvil.packetforge.commons.*;
import org.stackanvil.packetforge.protocol.packet.common.*;
import org.stackanvil.packetforge.protocol.packet.configuration.ClientboundFinishConfigurationPacket;
import org.stackanvil.packetforge.protocol.packet.game.*;
import org.stackanvil.packetforge.protocol.packet.login.ServerboundHelloPacket;
import org.stackanvil.packetforge.protocol.packet.ping.ServerboundPingRequestPacket;
import org.stackanvil.packetforge.protocol.type.world.waypoints.*;

/** The generator stays static. Only this test loads vanilla in an isolated class loader. */
class VanillaCompatibilityTest {
    @Test
    void generatedBodiesAgreeWithVanillaDecodersAndEncoders() throws Exception {
        Path directory = Path.of(System.getProperty("packetforge.cache"), System.getProperty("packetforge.version"));
        List<URL> urls = new ArrayList<>();
        urls.add(directory.resolve("minecraft.jar").toUri().toURL());
        try (Stream<Path> libraries = Files.walk(directory.resolve("libraries"))) {
            for (Path library : libraries
                    .filter(p -> p.toString().endsWith(".jar"))
                    .sorted()
                    .toList()) urls.add(library.toUri().toURL());
        }
        List<Packet> samples = new ArrayList<>(List.of(
                new ClientboundKeepAlivePacket(Long.MIN_VALUE),
                new ClientboundSectionBlocksUpdatePacket(
                        Long.MIN_VALUE, new short[] {0, 4095, 123}, new int[] {0, 1, 2}),
                new ClientboundSetEquipmentPacket(
                        123,
                        List.of(
                                new Pair<>(
                                        org.stackanvil.packetforge.protocol.type.world.entity.EquipmentSlot.MAINHAND,
                                        new ItemStack(1, 1, ComponentPatch.EMPTY)),
                                new Pair<>(
                                        org.stackanvil.packetforge.protocol.type.world.entity.EquipmentSlot.HEAD,
                                        ItemStack.EMPTY))),
                new ClientboundMoveEntityPacket_Pos(
                        123, new MovementDelta.Linear(Short.MIN_VALUE, (short) 0, Short.MAX_VALUE), true),
                new ClientboundMoveEntityPacket_PosRot(
                        123,
                        new MovementDelta.Stepped(List.of(
                                new MovementDelta.Step((short) -1, (short) 2, (short) -3, 0),
                                new MovementDelta.Step(Short.MAX_VALUE, Short.MIN_VALUE, (short) 0, 128))),
                        (byte) -128,
                        (byte) 127,
                        false),
                new ClientboundTransferPacket("localhost", 25565),
                new ClientboundTransferPacket("münchen.example", 65535),
                new ClientboundSetHealthPacket(17.5f, 20, 3.25f),
                new ClientboundAnimatePacket(345, 255),
                new ClientboundBlockChangedAckPacket(-1),
                new ClientboundSetChunkCacheCenterPacket(-1234, 5678),
                new ClientboundOpenSignEditorPacket(
                        new BlockPosition(-33554432, -2048, 33554431),
                        org.stackanvil.packetforge.protocol.type.world.level.block.entity.SignTextSlot.FRONT),
                new ServerboundHelloPacket("Player", UUID.fromString("12345678-9abc-def0-1234-56789abcdef0")),
                new ClientboundResetScorePacket("owner", null),
                new ClientboundResetScorePacket("owner", "objective"),
                new ClientboundCustomChatCompletionsPacket(
                        ClientboundCustomChatCompletionsPacket_Action.REMOVE, List.of("hello", "café")),
                new ClientboundCooldownPacket("minecraft:stone", 128),
                new ServerboundClientCommandPacket(ServerboundClientCommandPacket_Action.REQUEST_STATS),
                new ServerboundUseItemPacket(
                        org.stackanvil.packetforge.protocol.type.world.InteractionHand.OFF_HAND, 12345, 180f, -90f),
                new ServerboundPingRequestPacket(Long.MAX_VALUE),
                new ClientboundFinishConfigurationPacket(),
                new ClientboundDamageEventPacket(123, 0, -1, 456, java.util.Optional.of(new Vector3(1.25, -2.5, 3.75))),
                new ClientboundDamageEventPacket(123, 0, Integer.MAX_VALUE, -1, java.util.Optional.empty()),
                new ClientboundSetPlayerTeamPacket(
                        (byte) 3, "team", List.of("player", "café"), java.util.Optional.empty()),
                new ClientboundSetPlayerTeamPacket((byte) 4, "team", List.of("player"), java.util.Optional.empty())));
        List<Variant<TrackedWaypoint_Type>> waypointContents = List.of(
                new Variant<>(TrackedWaypoint_Type.EMPTY, Unit.INSTANCE),
                new Variant<>(
                        TrackedWaypoint_Type.VEC3I,
                        new TrackedWaypoint_Vec3iWaypoint_Contents(
                                new IntVector3(Integer.MIN_VALUE, -1, Integer.MAX_VALUE))),
                new Variant<>(
                        TrackedWaypoint_Type.CHUNK,
                        new TrackedWaypoint_ChunkWaypoint_Contents(new ChunkPosition(-12345, 67890))),
                new Variant<>(TrackedWaypoint_Type.AZIMUTH, new TrackedWaypoint_AzimuthWaypoint_Contents(-3.1415927f)));
        for (var operation : ClientboundTrackedWaypointPacket_Operation.values())
            for (var contents : waypointContents)
                for (boolean named : new boolean[] {false, true}) {
                    Either<UUID, String> id = named
                            ? Either.right("waypoint café")
                            : Either.left(new UUID(Long.MIN_VALUE, Long.MAX_VALUE));
                    var icon = new Waypoint_Icon(
                            "minecraft:default",
                            named ? java.util.Optional.of(0xff123456) : java.util.Optional.empty());
                    samples.add(
                            new ClientboundTrackedWaypointPacket(operation, new TrackedWaypoint(id, icon, contents)));
                }
        var models = GeneratedRoundTripTest.models();
        var actionTable = models.values().stream()
                .filter(m -> m.has("originalExpression")
                        && m.get("originalExpression").getAsString().startsWith("enum-actions:"))
                .findFirst()
                .orElseThrow();
        for (int bits = 0; bits < 256; bits++)
            for (int variant = 0; variant < 3; variant++) {
                var actions = java.util.EnumSet.noneOf(ClientboundPlayerInfoUpdatePacket_Action.class);
                var values = new java.util.EnumMap<ClientboundPlayerInfoUpdatePacket_Action, Object>(
                        ClientboundPlayerInfoUpdatePacket_Action.class);
                for (var branchValue : actionTable.getAsJsonArray("cases")) {
                    var branch = branchValue.getAsJsonObject();
                    var action = ClientboundPlayerInfoUpdatePacket_Action.valueOf(
                            branch.get("key").getAsString());
                    if ((bits & 1 << action.ordinal()) == 0) continue;
                    actions.add(action);
                    var schema = branch.getAsJsonObject("value");
                    values.put(
                            action,
                            GeneratedRoundTripTest.sample(
                                    GeneratedRoundTripTest.type(
                                            schema.get("type").getAsString()),
                                    schema.get("shape").getAsString(),
                                    variant,
                                    models));
                }
                samples.add(new ClientboundPlayerInfoUpdatePacket(
                        actions,
                        List.of(new ActionEntry<>(new UUID(1, 2), values), new ActionEntry<>(new UUID(3, 4), values))));
            }
        var arguments = models.values().stream()
                .filter(m ->
                        m.has("registry") && m.get("registry").getAsString().equals("COMMAND_ARGUMENT_TYPE"))
                .findFirst()
                .orElseThrow();
        for (var branchValue : arguments.getAsJsonArray("cases"))
            for (int variant = 0; variant < 3; variant++)
                for (boolean suggestions : new boolean[] {false, true}) {
                    var branch = branchValue.getAsJsonObject();
                    var schema = branch.getAsJsonObject("value");
                    Object value = GeneratedRoundTripTest.sample(
                            GeneratedRoundTripTest.type(schema.get("type").getAsString()),
                            schema.get("shape").getAsString(),
                            variant,
                            models);
                    var argument = new CommandNode.Argument(
                            "argument café",
                            new Variant<>(branch.get("key").getAsInt(), value),
                            suggestions ? "minecraft:ask_server" : null);
                    samples.add(new ClientboundCommandsPacket(
                            List.of(
                                    new CommandNode(0, new int[] {1}, 0, null),
                                    new CommandNode(46 | (suggestions ? 16 : 0), new int[0], 2, argument),
                                    new CommandNode(1, new int[0], 0, new CommandNode.Literal("literal"))),
                            0));
                }
        for (int flags = 0; flags < 256; flags++) {
            CommandNode.Stub stub = (flags & 3) == 2
                    ? new CommandNode.Argument(
                            "argument",
                            new Variant<>(0, Unit.INSTANCE),
                            (flags & 16) != 0 ? "minecraft:ask_server" : null)
                    : (flags & 3) == 1 ? new CommandNode.Literal("literal") : null;
            samples.add(new ClientboundCommandsPacket(List.of(new CommandNode(flags, new int[0], 9, stub)), 0));
        }
        for (var entry : GeneratedProtocol.registry().entries().values()) {
            for (int variant = 0; variant < 3; variant++)
                samples.add((Packet) GeneratedRoundTripTest.sample(entry.type(), "", variant, models));
            var model = models.get(entry.type().getName());
            if (model.has("program")
                    && !model.getAsJsonObject("program")
                            .getAsJsonArray("operations")
                            .toString()
                            .matches(".*(REGISTRY|NBT|JSON|holder).*")) {
                byte[] baseline = body((Packet) GeneratedRoundTripTest.sample(entry.type(), "", 0, models));
                for (int index = 0; index < Math.min(baseline.length, 128); index++)
                    for (int bit = 0; bit < 8; bit++) {
                        byte[] mutation = baseline.clone();
                        mutation[index] ^= (byte) (1 << bit);
                        ByteBuf input = Unpooled.wrappedBuffer(mutation);
                        try {
                            Packet packet = entry.codec().decode(input);
                            if (!input.isReadable()) samples.add(packet);
                        } catch (IllegalArgumentException | IndexOutOfBoundsException ignored) {
                        } finally {
                            input.release();
                        }
                    }
            }
        }
        System.out.println("Comparing " + samples.size() + " generated packet bodies with vanilla codecs");
        try (URLClassLoader loader =
                new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            Class<?> byteBuf = loader.loadClass("io.netty.buffer.ByteBuf");
            Class<?> unpooled = loader.loadClass("io.netty.buffer.Unpooled");
            Class<?> friendly = loader.loadClass("net.minecraft.network.RegistryFriendlyByteBuf");
            Class<?> registryAccess = loader.loadClass("net.minecraft.core.RegistryAccess");
            loader.loadClass("net.minecraft.SharedConstants")
                    .getMethod("tryDetectVersion")
                    .invoke(null);
            loader.loadClass("net.minecraft.server.Bootstrap")
                    .getMethod("bootStrap")
                    .invoke(null);
            Object builtIns = loader.loadClass("net.minecraft.core.registries.BuiltInRegistries")
                    .getField("REGISTRY")
                    .get(null);
            Object registries = worldRegistries(loader, builtIns, models);
            auditPacketIds(loader);
            Class<?> streamCodec = loader.loadClass("net.minecraft.network.codec.StreamCodec");
            Method decode = streamCodec.getMethod("decode", Object.class);
            Method encode = streamCodec.getMethod("encode", Object.class, Object.class);
            for (Packet packet : samples) {
                var metadata = models.get(packet.getClass().getName());
                String original = metadata.get("originalName").getAsString().split("#", 2)[0];
                Class<?> vanilla = loadOriginal(loader, original);
                Object codec = loader.loadClass("net.minecraft.network.protocol.BundleDelimiterPacket")
                                .isAssignableFrom(vanilla)
                        ? streamCodec
                                .getMethod("unit", Object.class)
                                .invoke(null, vanilla.getConstructor().newInstance())
                        : vanilla.getField(metadata.get("sourceCodecField").getAsString())
                                .get(null);
                byte[] expected = body(packet);
                Object inputRaw =
                        unpooled.getMethod("wrappedBuffer", byte[].class).invoke(null, (Object) expected);
                Object outputRaw = unpooled.getMethod("buffer").invoke(null);
                try {
                    Object input =
                            friendly.getConstructor(byteBuf, registryAccess).newInstance(inputRaw, registries);
                    Object output =
                            friendly.getConstructor(byteBuf, registryAccess).newInstance(outputRaw, registries);
                    Object vanillaPacket = decode.invoke(codec, input);
                    assertEquals(0, byteBuf.getMethod("readableBytes").invoke(inputRaw), original);
                    encode.invoke(codec, output, vanillaPacket);
                    int length = (int) byteBuf.getMethod("readableBytes").invoke(outputRaw);
                    byte[] actual = new byte[length];
                    byteBuf.getMethod("readBytes", byte[].class).invoke(outputRaw, (Object) actual);
                    if (!java.util.Arrays.equals(expected, actual))
                        assertSameWireValues(packet.getClass(), expected, actual, original);
                } catch (Exception e) {
                    throw new AssertionError(original, e);
                } finally {
                    byteBuf.getMethod("release").invoke(inputRaw);
                    byteBuf.getMethod("release").invoke(outputRaw);
                }
            }
            auditDispatches(loader, models, registries, byteBuf, unpooled, friendly, registryAccess, decode, encode);
            auditComponentPatches(
                    loader, models, registries, byteBuf, unpooled, friendly, registryAccess, decode, encode);
            auditRelatedValues(loader, models, registries, byteBuf, unpooled, friendly, registryAccess, decode, encode);
            VanillaVariantAudit.run(
                    loader, models, registries, byteBuf, unpooled, friendly, registryAccess, decode, encode);
        }
    }

    @Test
    void registryKeepsTheCompleteCatalogueAndBundleOffset() {
        assertEquals(777, GeneratedProtocol.PROTOCOL_VERSION);
        assertEquals(260, GeneratedProtocol.DESCRIPTORS.size());
        assertEquals(260, GeneratedProtocol.registry().entries().size());
        assertTrue(GeneratedProtocol.DESCRIPTORS.stream().allMatch(GeneratedProtocol.Descriptor::supported));
        var play = GeneratedProtocol.DESCRIPTORS.stream()
                .filter(d -> d.key().state() == PacketRegistry.State.PLAY
                        && d.key().flow() == PacketRegistry.Flow.CLIENTBOUND)
                .toList();
        assertEquals("minecraft:bundle_delimiter", play.getFirst().name());
        assertEquals(0, play.getFirst().key().id());
        assertEquals(1, play.get(1).key().id());
        for (var descriptor : GeneratedProtocol.DESCRIPTORS) {
            assertEquals(
                    descriptor.supported(),
                    GeneratedProtocol.registry().entries().containsKey(descriptor.key()));
            if (!descriptor.supported()) assertFalse(descriptor.reason().isBlank());
        }
    }

    private static void auditPacketIds(ClassLoader loader) throws Exception {
        java.util.Set<String> owners = new java.util.TreeSet<>();
        try (var input = GeneratedProtocol.class.getResourceAsStream("/packetforge/protocol.json");
                var reader = new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8)) {
            var report = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
            for (var registration : report.getAsJsonArray("registrations"))
                owners.add(registration.getAsJsonObject().get("source").getAsString());
        }
        Class<?> provider = loader.loadClass("net.minecraft.network.ProtocolInfo$DetailsProvider"),
                detailsType = loader.loadClass("net.minecraft.network.ProtocolInfo$Details"),
                visitor = loader.loadClass("net.minecraft.network.ProtocolInfo$Details$PacketVisitor");
        Class<?> packetType = loader.loadClass("net.minecraft.network.protocol.PacketType");
        var actual = new java.util.HashMap<PacketRegistry.Key, String>();
        for (String owner : owners)
            for (var field : loader.loadClass(owner).getFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        || !provider.isAssignableFrom(field.getType())) continue;
                Object details = provider.getMethod("details").invoke(field.get(null));
                var state = PacketRegistry.State.valueOf(
                        ((Enum<?>) detailsType.getMethod("id").invoke(details)).name());
                var flow = PacketRegistry.Flow.valueOf(
                        ((Enum<?>) detailsType.getMethod("flow").invoke(details)).name());
                Object callback = java.lang.reflect.Proxy.newProxyInstance(
                        loader, new Class<?>[] {visitor}, (proxy, method, arguments) -> {
                            var key = new PacketRegistry.Key(state, flow, (Integer) arguments[1]);
                            assertNull(
                                    actual.put(
                                            key,
                                            packetType
                                                    .getMethod("id")
                                                    .invoke(arguments[0])
                                                    .toString()),
                                    "Duplicate vanilla packet ID " + key);
                            return null;
                        });
                detailsType.getMethod("listPackets", visitor).invoke(details, callback);
            }
        var expected = new java.util.HashMap<PacketRegistry.Key, String>();
        for (var descriptor : GeneratedProtocol.DESCRIPTORS) expected.put(descriptor.key(), descriptor.name());
        assertEquals(expected, actual, "Every generated packet state, direction, ID, and name must match vanilla");
        System.out.println("Verified " + actual.size() + " packet IDs against vanilla protocol details");
    }

    private static void auditDispatches(
            ClassLoader loader,
            java.util.Map<String, com.google.gson.JsonObject> models,
            Object registries,
            Class<?> byteBuf,
            Class<?> unpooled,
            Class<?> friendly,
            Class<?> registryAccess,
            Method decode,
            Method encode)
            throws Exception {
        Class<?> registryType = loader.loadClass("net.minecraft.core.Registry");
        int compared = 0;
        for (var definition : models.entrySet()) {
            if (!definition.getKey().startsWith("$dispatch:")) continue;
            var dispatch = definition.getValue();
            String name = dispatch.get("name").getAsString();
            boolean commandArgument = dispatch.get("registry").getAsString().equals("COMMAND_ARGUMENT_TYPE");
            boolean enumActions =
                    dispatch.get("originalExpression").getAsString().startsWith("enum-actions:");
            boolean enumConstructor =
                    dispatch.get("originalExpression").getAsString().startsWith("enum-constructor:");
            boolean enumOperation =
                    dispatch.get("originalExpression").getAsString().startsWith("enum-decoder:");
            boolean entityMetadata = dispatch.get("registry").getAsString().equals("ENTITY_DATA_SERIALIZERS");
            Class<?> serializerTable =
                    entityMetadata ? loader.loadClass("net.minecraft.network.syncher.EntityDataSerializers") : null;
            Object defaultOriginal =
                    entityMetadata || enumOperation || enumConstructor || enumActions || commandArgument
                            ? null
                            : loadOriginal(loader, dispatch.get("sourceOwner").getAsString())
                                    .getField(dispatch.get("sourceCodecField").getAsString())
                                    .get(null);
            @SuppressWarnings("unchecked")
            PacketCodec<Object> generated = (PacketCodec<Object>)
                    GeneratedWireCodecs.class.getField(name).get(null);
            var cases = dispatch.getAsJsonArray("cases");
            String registryName = dispatch.get("registry").getAsString();
            String[] registryNames =
                    registryName.isEmpty() || entityMetadata ? new String[0] : registryName.split("\\|");
            if (entityMetadata)
                assertNull(
                        serializerTable.getMethod("getSerializer", int.class).invoke(null, cases.size()),
                        "Unrecovered entity serializers");
            int registryCases = 0;
            for (String registryField : registryNames)
                registryCases += (int) registryType
                        .getMethod("size")
                        .invoke(loader.loadClass("net.minecraft.core.registries.BuiltInRegistries")
                                .getField(registryField)
                                .get(null));
            if (registryNames.length > 0) assertEquals(cases.size(), registryCases, registryName + " table size");
            java.lang.reflect.Type keyType = GeneratedRoundTripTest.type(
                    dispatch.getAsJsonObject("key").get("type").getAsString());
            for (var entry : cases) {
                var branch = entry.getAsJsonObject();
                String keyName = branch.get("key").getAsString();
                Object key = GeneratedRoundTripTest.dispatchKey(keyType, keyName);
                Object original = defaultOriginal;
                if (entityMetadata) {
                    Object serializer = serializerTable
                            .getMethod("getSerializer", int.class)
                            .invoke(null, (Integer) key);
                    assertSame(
                            serializerTable
                                    .getField(branch.get("name").getAsString())
                                    .get(null),
                            serializer,
                            "Entity serializer ID " + key);
                    original = loader.loadClass("net.minecraft.network.syncher.EntityDataSerializer")
                            .getMethod("codec")
                            .invoke(serializer);
                }
                if (commandArgument)
                    original = registryType
                            .getMethod("byId", int.class)
                            .invoke(
                                    loader.loadClass("net.minecraft.core.registries.BuiltInRegistries")
                                            .getField("COMMAND_ARGUMENT_TYPE")
                                            .get(null),
                                    (Integer) key);
                final Object originalCodec = original;
                if (registryNames.length > 0) {
                    String registryField = registryNames.length == 1
                            ? registryNames[0]
                            : registryNames[keyName.startsWith("left:") ? 0 : 1];
                    Object registry = loader.loadClass("net.minecraft.core.registries.BuiltInRegistries")
                            .getField(registryField)
                            .get(null);
                    int id = Integer.parseInt(keyName.substring(keyName.indexOf(':') + 1));
                    Object value = registryType.getMethod("byId", int.class).invoke(registry, id);
                    assertEquals(
                            branch.get("name").getAsString(),
                            registryType
                                    .getMethod("getKey", Object.class)
                                    .invoke(registry, value)
                                    .toString(),
                            registryName + " ID " + keyName);
                }
                var schema = branch.getAsJsonObject("value");
                if (schema.get("shape").getAsString().startsWith("REJECTED:")) {
                    ByteBuf keyBytes = Unpooled.buffer();
                    try {
                        WireCodecs.VAR_INT.encode(keyBytes, (Integer) key);
                        byte[] bytes = new byte[keyBytes.readableBytes()];
                        keyBytes.getBytes(0, bytes);
                        assertThrows(IllegalArgumentException.class, () -> generated.decode(keyBytes));
                        Object raw = unpooled.getMethod("wrappedBuffer", byte[].class)
                                .invoke(null, (Object) bytes);
                        try {
                            Object input = friendly.getConstructor(byteBuf, registryAccess)
                                    .newInstance(raw, registries);
                            assertThrows(
                                    java.lang.reflect.InvocationTargetException.class,
                                    () -> decode.invoke(originalCodec, input));
                        } finally {
                            byteBuf.getMethod("release").invoke(raw);
                        }
                    } finally {
                        keyBytes.release();
                    }
                    continue;
                }
                for (int variant = 0; variant < 3; variant++) {
                    Object value = GeneratedRoundTripTest.sample(
                            GeneratedRoundTripTest.type(schema.get("type").getAsString()),
                            schema.get("shape").getAsString(),
                            variant,
                            models);
                    ByteBuf buffer = Unpooled.buffer();
                    byte[] expected;
                    try {
                        generated.encode(buffer, new Variant<>(key, value));
                        expected = new byte[buffer.readableBytes()];
                        buffer.readBytes(expected);
                    } finally {
                        buffer.release();
                    }
                    Object
                            inputRaw =
                                    unpooled.getMethod("wrappedBuffer", byte[].class)
                                            .invoke(null, (Object) expected),
                            outputRaw = unpooled.getMethod("buffer").invoke(null);
                    try {
                        Object
                                input =
                                        friendly.getConstructor(byteBuf, registryAccess)
                                                .newInstance(inputRaw, registries),
                                output =
                                        friendly.getConstructor(byteBuf, registryAccess)
                                                .newInstance(outputRaw, registries);
                        if (entityMetadata || commandArgument) {
                            Class<?> varInt = loader.loadClass("net.minecraft.network.VarInt");
                            assertEquals(key, varInt.getMethod("read", byteBuf).invoke(null, inputRaw));
                            varInt.getMethod("write", byteBuf, int.class).invoke(null, outputRaw, (Integer) key);
                        }
                        if (enumOperation || enumConstructor || enumActions) {
                            Class<?> enumeration = loadOriginal(
                                    loader, dispatch.get("sourceOwner").getAsString());
                            Object sourceKey = java.util.Arrays.stream(enumeration.getEnumConstants())
                                    .filter(v -> ((Enum<?>) v).name().equals(keyName))
                                    .findFirst()
                                    .orElseThrow();
                            Class<?> varInt = loader.loadClass("net.minecraft.network.VarInt");
                            assertEquals(
                                    ((Enum<?>) sourceKey).ordinal(),
                                    varInt.getMethod("read", byteBuf).invoke(null, inputRaw));
                            var field = enumeration.getDeclaredField(dispatch.get("originalExpression")
                                    .getAsString()
                                    .split(":")[1]);
                            field.setAccessible(true);
                            Object parsed;
                            if (enumActions) {
                                Class<?> builderType = loadOriginal(
                                        loader,
                                        dispatch.get("sourceOwner")
                                                .getAsString()
                                                .replace(".Action", ".EntryBuilder"));
                                var constructor = builderType.getDeclaredConstructor(UUID.class);
                                constructor.setAccessible(true);
                                Object builder = constructor.newInstance(new UUID(0, 0));
                                Class<?> readerType = loadOriginal(
                                        loader, dispatch.get("sourceOwner").getAsString() + ".Reader");
                                readerType
                                        .getMethod("read", builderType, friendly)
                                        .invoke(field.get(sourceKey), builder, input);
                                var build = builderType.getDeclaredMethod("build");
                                build.setAccessible(true);
                                parsed = build.invoke(builder);
                            } else if (enumConstructor) {
                                Object identifier = loader.loadClass("com.mojang.datafixers.util.Either")
                                        .getMethod("left", Object.class)
                                        .invoke(null, new UUID(0, 0));
                                Object icon = loader.loadClass("net.minecraft.world.waypoints.Waypoint$Icon")
                                        .getField("NULL")
                                        .get(null);
                                parsed = loader.loadClass("org.apache.commons.lang3.function.TriFunction")
                                        .getMethod("apply", Object.class, Object.class, Object.class)
                                        .invoke(field.get(sourceKey), identifier, icon, input);
                            } else
                                parsed = loader.loadClass("net.minecraft.network.codec.StreamDecoder")
                                        .getMethod("decode", Object.class)
                                        .invoke(field.get(sourceKey), input);
                            varInt.getMethod("write", byteBuf, int.class)
                                    .invoke(null, outputRaw, ((Enum<?>) sourceKey).ordinal());
                            if (enumActions) {
                                var writerField = enumeration.getDeclaredField(dispatch.get("originalExpression")
                                        .getAsString()
                                        .split(":")[2]);
                                writerField.setAccessible(true);
                                Class<?> writerType = loadOriginal(
                                        loader, dispatch.get("sourceOwner").getAsString() + ".Writer");
                                writerType
                                        .getMethod("write", friendly, parsed.getClass())
                                        .invoke(writerField.get(sourceKey), output, parsed);
                            } else {
                                var writer = java.util.Arrays.stream(
                                                parsed.getClass().getDeclaredMethods())
                                        .filter(m -> m.getName()
                                                        .equals(dispatch.get("originalExpression")
                                                                .getAsString()
                                                                .split(":")[2])
                                                && m.getParameterCount() == 1
                                                && !m.isBridge())
                                        .findFirst()
                                        .orElseThrow();
                                writer.setAccessible(true);
                                writer.invoke(parsed, output);
                            }
                        } else if (commandArgument) {
                            var reader = java.util.Arrays.stream(
                                            originalCodec.getClass().getMethods())
                                    .filter(m -> m.getName().equals("deserializeFromNetwork") && !m.isBridge())
                                    .findFirst()
                                    .orElseThrow();
                            reader.setAccessible(true);
                            Object parsed = reader.invoke(originalCodec, input);
                            var writer = java.util.Arrays.stream(
                                            originalCodec.getClass().getMethods())
                                    .filter(m -> m.getName().equals("serializeToNetwork") && !m.isBridge())
                                    .findFirst()
                                    .orElseThrow();
                            writer.setAccessible(true);
                            writer.invoke(originalCodec, parsed, output);
                        } else {
                            Object parsed = decode.invoke(originalCodec, input);
                            encode.invoke(originalCodec, output, parsed);
                        }
                        assertEquals(0, byteBuf.getMethod("readableBytes").invoke(inputRaw));
                        byte[] actual = new byte
                                [(int) byteBuf.getMethod("readableBytes").invoke(outputRaw)];
                        byteBuf.getMethod("readBytes", byte[].class).invoke(outputRaw, (Object) actual);
                        if (!java.util.Arrays.equals(expected, actual))
                            assertSameWireValues(generated, expected, actual, name + " / " + branch.get("name"));
                        compared++;
                    } catch (Exception failure) {
                        throw new AssertionError(name + " / " + branch.get("name") + " / variant " + variant, failure);
                    } finally {
                        byteBuf.getMethod("release").invoke(inputRaw);
                        byteBuf.getMethod("release").invoke(outputRaw);
                    }
                }
            }
        }
        System.out.println(
                "Verified " + compared + " dispatch payloads and every recovered registry ID against vanilla");
    }

    private static void auditComponentPatches(
            ClassLoader loader,
            java.util.Map<String, com.google.gson.JsonObject> models,
            Object registries,
            Class<?> byteBuf,
            Class<?> unpooled,
            Class<?> friendly,
            Class<?> registryAccess,
            Method decode,
            Method encode)
            throws Exception {
        var definition = models.values().stream()
                .filter(v ->
                        v.has("registry") && v.get("registry").getAsString().equals("DATA_COMPONENT_TYPE"))
                .findFirst()
                .orElseThrow();
        int compared = 0;
        for (boolean delimited : new boolean[] {false, true}) {
            PacketCodec<ComponentPatch> codec = ComponentCodecs.patch(DataComponentTypes.values(), delimited);
            Object source = loader.loadClass("net.minecraft.core.component.DataComponentPatch")
                    .getField(delimited ? "DELIMITED_STREAM_CODEC" : "STREAM_CODEC")
                    .get(null);
            for (var entry : definition.getAsJsonArray("cases"))
                for (int variant = 0; variant < 3; variant++) {
                    var branch = entry.getAsJsonObject();
                    int id = branch.get("key").getAsInt();
                    var schema = branch.getAsJsonObject("value");
                    Object value = GeneratedRoundTripTest.sample(
                            GeneratedRoundTripTest.type(schema.get("type").getAsString()),
                            schema.get("shape").getAsString(),
                            variant,
                            models);
                    ComponentPatch patch = ComponentPatch.of(sampleComponent(DataComponentTypes.byId(id), value));
                    for (ComponentPatch sample : List.of(
                            patch,
                            ComponentPatch.builder()
                                    .remove(DataComponentTypes.byId(id))
                                    .build())) {
                        ByteBuf encoded = Unpooled.buffer();
                        byte[] expected;
                        try {
                            codec.encode(encoded, sample);
                            expected = new byte[encoded.readableBytes()];
                            encoded.readBytes(expected);
                        } finally {
                            encoded.release();
                        }
                        Object
                                inputRaw =
                                        unpooled.getMethod("wrappedBuffer", byte[].class)
                                                .invoke(null, (Object) expected),
                                outputRaw = unpooled.getMethod("buffer").invoke(null);
                        try {
                            Object
                                    input =
                                            friendly.getConstructor(byteBuf, registryAccess)
                                                    .newInstance(inputRaw, registries),
                                    output =
                                            friendly.getConstructor(byteBuf, registryAccess)
                                                    .newInstance(outputRaw, registries);
                            Object parsed = decode.invoke(source, input);
                            assertEquals(0, byteBuf.getMethod("readableBytes").invoke(inputRaw));
                            encode.invoke(source, output, parsed);
                            byte[] actual = new byte
                                    [(int) byteBuf.getMethod("readableBytes").invoke(outputRaw)];
                            byteBuf.getMethod("readBytes", byte[].class).invoke(outputRaw, (Object) actual);
                            if (!java.util.Arrays.equals(expected, actual)) {
                                ByteBuf left = Unpooled.wrappedBuffer(expected), right = Unpooled.wrappedBuffer(actual);
                                try {
                                    assertTrue(
                                            sameValue(codec.decode(left), codec.decode(right)),
                                            "Component patch changed wire values");
                                    assertFalse(left.isReadable());
                                    assertFalse(right.isReadable());
                                } finally {
                                    left.release();
                                    right.release();
                                }
                            }
                            compared++;
                        } catch (Exception failure) {
                            throw new AssertionError(
                                    branch.get("name") + " / delimited=" + delimited + " / " + variant, failure);
                        } finally {
                            byteBuf.getMethod("release").invoke(inputRaw);
                            byteBuf.getMethod("release").invoke(outputRaw);
                        }
                    }
                }
        }
        System.out.println("Verified "
                + compared
                + " component patches against vanilla, including delimited payloads and removals");
    }

    private static void auditRelatedValues(
            ClassLoader loader,
            java.util.Map<String, com.google.gson.JsonObject> models,
            Object registries,
            Class<?> byteBuf,
            Class<?> unpooled,
            Class<?> friendly,
            Class<?> registryAccess,
            Method decode,
            Method encode)
            throws Exception {
        int compared = 0;
        for (String original : List.of(
                "net.minecraft.world.effect.MobEffectInstance",
                "net.minecraft.world.effect.MobEffectInstance.Details",
                "net.minecraft.advancements.DisplayInfo")) {
            var model = models.values().stream()
                    .filter(m -> m.has("originalName")
                            && m.get("originalName").getAsString().equals(original))
                    .findFirst()
                    .orElseThrow();
            Class<?> generated = Class.forName(model.get("generatedName").getAsString());
            @SuppressWarnings("unchecked")
            PacketCodec<Object> codec =
                    (PacketCodec<Object>) generated.getField("CODEC").get(null);
            var field = loadOriginal(loader, original).getDeclaredField("STREAM_CODEC");
            field.setAccessible(true);
            Object source = field.get(null);
            for (int variant = 0; variant < 3; variant++) {
                ByteBuf encoded = Unpooled.buffer();
                byte[] expected;
                try {
                    codec.encode(
                            encoded, GeneratedRoundTripTest.sample(generated, "model:" + original, variant, models));
                    expected = new byte[encoded.readableBytes()];
                    encoded.readBytes(expected);
                } finally {
                    encoded.release();
                }
                Object
                        inputRaw =
                                unpooled.getMethod("wrappedBuffer", byte[].class)
                                        .invoke(null, (Object) expected),
                        outputRaw = unpooled.getMethod("buffer").invoke(null);
                try {
                    Object
                            input =
                                    friendly.getConstructor(byteBuf, registryAccess)
                                            .newInstance(inputRaw, registries),
                            output =
                                    friendly.getConstructor(byteBuf, registryAccess)
                                            .newInstance(outputRaw, registries);
                    Object parsed = decode.invoke(source, input);
                    assertEquals(0, byteBuf.getMethod("readableBytes").invoke(inputRaw));
                    encode.invoke(source, output, parsed);
                    byte[] actual =
                            new byte[(int) byteBuf.getMethod("readableBytes").invoke(outputRaw)];
                    byteBuf.getMethod("readBytes", byte[].class).invoke(outputRaw, (Object) actual);
                    assertArrayEquals(expected, actual, original + " recursive variant " + variant);
                    compared++;
                } finally {
                    byteBuf.getMethod("release").invoke(inputRaw);
                    byteBuf.getMethod("release").invoke(outputRaw);
                }
            }
        }
        var components = models.values().stream()
                .filter(m ->
                        m.has("registry") && m.get("registry").getAsString().equals("DATA_COMPONENT_TYPE"))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        var schemas = (java.util.Map<Integer, PacketCodec<?>>) GeneratedWireCodecs.class
                .getMethod(components.get("name").getAsString() + "_payloads")
                .invoke(null);
        for (String original : List.of(
                "net.minecraft.world.entity.animal.rabbit.Rabbit.Variant",
                "net.minecraft.world.entity.animal.fish.TropicalFish.Pattern")) {
            var branch = java.util.stream.StreamSupport.stream(
                            components.getAsJsonArray("cases").spliterator(), false)
                    .map(e -> e.getAsJsonObject())
                    .filter(c -> c.getAsJsonObject("value")
                            .get("shape")
                            .getAsString()
                            .equals("enum:" + original))
                    .findFirst()
                    .orElseThrow();
            @SuppressWarnings("unchecked")
            PacketCodec<Object> generated =
                    (PacketCodec<Object>) schemas.get(branch.get("key").getAsInt());
            Class<?> valueType = (Class<?>) GeneratedRoundTripTest.type(
                    branch.getAsJsonObject("value").get("type").getAsString());
            Object source =
                    loadOriginal(loader, original).getField("STREAM_CODEC").get(null);
            List<byte[]> bodies = new ArrayList<>();
            for (Object constant : valueType.getEnumConstants()) {
                ByteBuf buffer = Unpooled.buffer();
                try {
                    generated.encode(buffer, constant);
                    byte[] bytes = new byte[buffer.readableBytes()];
                    buffer.readBytes(bytes);
                    bodies.add(bytes);
                } finally {
                    buffer.release();
                }
            }
            for (int unknown : new int[] {-1, 100000}) {
                ByteBuf buffer = Unpooled.buffer();
                try {
                    WireCodecs.VAR_INT.encode(buffer, unknown);
                    byte[] bytes = new byte[buffer.readableBytes()];
                    buffer.readBytes(bytes);
                    bodies.add(bytes);
                } finally {
                    buffer.release();
                }
            }
            for (byte[] bytes : bodies) {
                ByteBuf input = Unpooled.wrappedBuffer(bytes);
                Object expected;
                try {
                    expected = generated.decode(input);
                    assertFalse(input.isReadable());
                } finally {
                    input.release();
                }
                Object raw = unpooled.getMethod("wrappedBuffer", byte[].class).invoke(null, (Object) bytes);
                try {
                    Object parsed = decode.invoke(
                            source,
                            friendly.getConstructor(byteBuf, registryAccess).newInstance(raw, registries));
                    assertEquals(((Enum<?>) expected).name(), ((Enum<?>) parsed).name(), original);
                    assertEquals(0, byteBuf.getMethod("readableBytes").invoke(raw));
                    compared++;
                } finally {
                    byteBuf.getMethod("release").invoke(raw);
                }
            }
        }
        System.out.println("Verified " + compared + " recursive effect and sparse-enum values against vanilla");
    }

    private static void assertSameWireValues(Class<?> packet, byte[] expected, byte[] actual, String name)
            throws Exception {
        @SuppressWarnings("unchecked")
        PacketCodec<Object> codec =
                (PacketCodec<Object>) packet.getField("CODEC").get(null);
        assertSameWireValues(codec, expected, actual, name);
    }

    static void assertSameWireValues(PacketCodec<Object> codec, byte[] expected, byte[] actual, String name)
            throws Exception {
        ByteBuf left = Unpooled.wrappedBuffer(expected), right = Unpooled.wrappedBuffer(actual);
        try {
            Object before = codec.decode(left), after = codec.decode(right);
            assertTrue(sameValue(before, after), name + " changed decoded wire values: " + before + " -> " + after);
            assertFalse(left.isReadable(), name);
            assertFalse(right.isReadable(), name);
        } finally {
            left.release();
            right.release();
        }
    }

    private static <T> DataComponent<T> sampleComponent(DataComponentType<T> type, Object sample) {
        // Reflection creates the sample from the type recorded in the extraction report.
        @SuppressWarnings("unchecked")
        T value = (T) sample;
        return type.withValue(value);
    }

    private static boolean sameValue(Object left, Object right) throws Exception {
        if (left == null || right == null) return left == right;
        if (!left.getClass().equals(right.getClass())) return false;
        if (left instanceof java.util.Map<?, ?> a && right instanceof java.util.Map<?, ?> b) {
            if (!a.keySet().equals(b.keySet())) return false;
            for (Object key : a.keySet()) if (!sameValue(a.get(key), b.get(key))) return false;
            return true;
        }
        if (left instanceof java.util.List<?> a && right instanceof java.util.List<?> b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!sameValue(a.get(i), b.get(i))) return false;
            return true;
        }
        if (left instanceof java.util.Optional<?> a && right instanceof java.util.Optional<?> b)
            return sameValue(a.orElse(null), b.orElse(null));
        if (left instanceof ComponentPatch a && right instanceof ComponentPatch b) {
            var aValues = a.added().stream()
                    .collect(java.util.stream.Collectors.toMap(DataComponent::type, DataComponent::value));
            var bValues = b.added().stream()
                    .collect(java.util.stream.Collectors.toMap(DataComponent::type, DataComponent::value));
            return a.removed().equals(b.removed()) && sameValue(aValues, bValues);
        }
        if (left.getClass().isRecord()) {
            for (var component : left.getClass().getRecordComponents())
                if (!sameValue(
                        component.getAccessor().invoke(left),
                        component.getAccessor().invoke(right))) return false;
            return true;
        }
        return java.util.Objects.deepEquals(left, right);
    }

    private static Object worldRegistries(
            ClassLoader loader, Object builtIns, java.util.Map<String, com.google.gson.JsonObject> models)
            throws Exception {
        Object provider = loader.loadClass("net.minecraft.data.registries.VanillaRegistries")
                .getMethod("createWorldLookup")
                .invoke(null);
        Class<?> providerType = loader.loadClass("net.minecraft.core.HolderLookup$Provider");
        Object initializers = loader.loadClass("net.minecraft.core.registries.BuiltInRegistries")
                .getField("DATA_COMPONENT_INITIALIZERS")
                .get(null);
        Class<?> initializerType = loader.loadClass("net.minecraft.core.component.DataComponentInitializers");
        Class<?> pendingType =
                loader.loadClass("net.minecraft.core.component.DataComponentInitializers$PendingComponents");
        for (Object pending :
                (Iterable<?>) initializerType.getMethod("build", providerType).invoke(initializers, provider))
            pendingType.getMethod("apply").invoke(pending);
        Class<?> lookupType = loader.loadClass("net.minecraft.core.HolderLookup");
        Class<?> resourceKey = loader.loadClass("net.minecraft.resources.ResourceKey");
        Class<?> holder = loader.loadClass("net.minecraft.core.Holder$Reference");
        Class<?> mapped = loader.loadClass("net.minecraft.core.MappedRegistry");
        Class<?> lifecycle = loader.loadClass("com.mojang.serialization.Lifecycle");
        Class<?> registration = loader.loadClass("net.minecraft.core.RegistrationInfo");
        Object stable = lifecycle.getMethod("stable").invoke(null);
        Object builtIn = registration.getField("BUILT_IN").get(null);
        java.util.Map<Object, Object> registries = new java.util.LinkedHashMap<>();
        Class<?> registryType = loader.loadClass("net.minecraft.core.Registry");
        for (Object registry : (Iterable<?>) builtIns)
            registries.put(registryType.getMethod("key").invoke(registry), registry);
        java.util.Set<Object> required = new java.util.HashSet<>();
        var registryFields = loader.loadClass("net.minecraft.core.registries.Registries");
        var pattern = java.util.regex.Pattern.compile("Registries\\.([A-Z_]+)");
        for (var model : models.values()) {
            var matches = pattern.matcher(model.toString());
            while (matches.find())
                required.add(registryFields.getField(matches.group(1)).get(null));
        }
        for (Object key : required) {
            if (registries.containsKey(key)) continue;
            Object lookup = providerType.getMethod("lookupOrThrow", resourceKey).invoke(provider, key);
            Object registry = mapped.getConstructor(resourceKey, lifecycle).newInstance(key, stable);
            try (Stream<?> elements =
                    (Stream<?>) lookupType.getMethod("listElements").invoke(lookup)) {
                for (Object reference : elements.toList())
                    mapped.getMethod("register", resourceKey, Object.class, registration)
                            .invoke(
                                    registry,
                                    holder.getMethod("key").invoke(reference),
                                    holder.getMethod("value").invoke(reference),
                                    builtIn);
            }
            mapped.getMethod("freeze").invoke(registry);
            registries.put(key, registry);
        }
        return loader.loadClass("net.minecraft.core.RegistryAccess$ImmutableRegistryAccess")
                .getConstructor(java.util.Map.class)
                .newInstance(registries);
    }

    private static Class<?> loadOriginal(ClassLoader loader, String name) throws ClassNotFoundException {
        String candidate = name;
        while (true) {
            try {
                return loader.loadClass(candidate);
            } catch (ClassNotFoundException e) {
                int dot = candidate.lastIndexOf('.');
                if (dot < 0) throw e;
                candidate = candidate.substring(0, dot) + "$" + candidate.substring(dot + 1);
            }
        }
    }

    private static byte[] body(Packet packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            packet.serialize(buffer);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }
}
