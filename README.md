# PacketForge

PacketForge generates a standalone Minecraft protocol library from the official server jar. It uses Vineflower and JavaParser to recover packet registrations and serialization operations.

This is an experimental generator, inspired by [MCProtocolLib](https://github.com/GeyserMC/MCProtocolLib). It is not a drop-in replacement. Minecraft 26.3 uses protocol version 777. The current generator emits codecs for **245 of 260 packet registrations**. It reports the other 15 with their IDs, source locations, and rejection reasons.

## Build

Install JDK 25, then run:

```sh
./gradlew build
```

The first build downloads the official server bundle and verifies its SHA-1 checksum against Mojang's version metadata. It extracts the server jar and bundled libraries, verifies their SHA-256 checksums, and decompiles the server jar with Vineflower 1.12.0. No Minecraft class runs during generation.

Minecraft files stay under `build/minecraft/26.3/`. Generated Java files stay under `protocol/build/generated/java/`. The complete coverage report is `protocol/build/generated/resources/packetforge/protocol.json`. Cached inputs and generated files are excluded from Git.

## Modules

| Module | Responsibility |
| --- | --- |
| `generator` | Download inputs, decompile, analyze Java syntax trees, lower wire operations, and emit Java records and codecs. |
| `commons` | Provide Netty buffer codecs, packet interfaces, packed block positions, and an immutable packet registry. |
| `protocol` | Generate and compile the 26.3 library. Publish it separately from the generator. |

The generated protocol depends on `commons`, Netty, and Gson. Minecraft, Vineflower, and JavaParser are absent from its runtime dependencies. Packet records expose a `CODEC` and implement `Packet.serialize(ByteBuf)`.

```java
import org.stackanvil.packetforge.protocol.GeneratedProtocol;
import org.stackanvil.packetforge.protocol.packet.common.ClientboundKeepAlivePacket;

var packet = new ClientboundKeepAlivePacket(123L);
packet.serialize(output); // Packet body, without its numeric ID.

var registry = GeneratedProtocol.registry();
var received = registry.decode(state, flow, completePacketBodyWithId);
```

Named packet codecs produce separate generated records when their layouts differ. Their names include the original codec field, such as `ClientboundShowDialogPacket_CONTEXT_FREE_STREAM_CODEC`. The report records that source field.

Play-state modifiers use an explicit context:

```java
var context = GeneratedProtocol.Context.of(false, true); // Command blocks, infinite materials.
var registry = GeneratedProtocol.registry(context);
```

`Context` is an interface. Stateful implementations can change their flags while the same registry stays in use. The registry checks each flag for every encode or decode. The parameterless `registry()` uses `Context.UNRESTRICTED`. `SkippedPacketException` identifies source rejections that allow the caller to skip the packet. The chat-only command codec retains its asymmetric limits: 256 characters when decoding and 32,500 when encoding.

`state` and `flow` use `PacketRegistry.State` and `PacketRegistry.Flow`. Supply one complete packet body to `registry.decode`. The registry reads the numeric ID and rejects trailing bytes. Framing, sessions, compression, encryption, authentication, and bundle assembly are outside this project.

## Extraction

`SourceIndex` resolves explicit imports and nested declarations without loading game classes. `Analyzer` walks each fluent protocol builder from its receiver outward. Registration order determines numeric IDs. A bundle delimiter consumes a wire ID. The enclosing builder determines protocol state and direction.

The analyzer lowers accepted codecs into a small intermediate representation. Each field records its original type, generated type, decoder, encoder, and wire shape. The emitter creates standalone source files from that representation. `ProgramLowerer` translates serialization bodies, constructor delegation, local variables, branches, bit operations, and static helpers with primitive or buffer parameters. It preserves explicit numeric narrowing and widening. It omits game listeners and unrelated methods.

Supported patterns include direct buffer constructors and writers, record constructor delegation, `Packet.codec`, `StreamCodec.unit`, and `StreamCodec.composite`, wrapper mappings, and constructor/writer pairs for nested data. Supported validation guards remain in generated constructors, including compact record constructors. Shared wire codecs cover primitives, variable integers, bounded UTF-8, UUIDs, identifiers, byte arrays, packed block positions, nullable values, optional values, maps, sets, fixed-size lists, and ordinary lists. UTF-8 decoding uses vanilla's replacement behavior for malformed byte sequences. Movement vectors use standalone value types and shared codecs for their full and compact representations. The analyzer also follows supported codec aliases and nested value codecs.

For linear layouts, the analyzer checks field order and matching read/write operations. For imperative layouts, it translates the control flow and compares wire operation kinds. It recovers ordinal enums, continuous and sparse enum ID maps, named enum values and their fallback rules, and matching enum switch expressions. Registry values use numeric wire IDs or `RegistryValue<T>` for references with an inline alternative. `HolderSet` represents registry tags and explicit ID lists. `ComponentPatch` holds added component wire values and removed component IDs. `ItemStack` holds its count, item ID, and component patch. Shared codecs support both ordinary and length-delimited component payloads. Entity metadata uses indexed `MetadataEntry` values and a terminating byte. The analyzer recovers all 44 serializer IDs from the explicit registration sequence. NBT-backed fields use `NbtTag`, and JSON-backed fields use Gson `JsonElement`. Applications interpret the registry IDs and NBT schemas. Generated records describe wire values, and the report records their original types and factories. Domain-only `StreamCodec.map` transformations retain the underlying wire type and record the source mapping as projection metadata.

Unknown packet layouts remain in `GeneratedProtocol.DESCRIPTORS` and the JSON report. They have no registered codec. Their original IDs remain reserved. Unknown registration operations fail generation because they can shift every later ID.

Enum and registry dispatch tables produce `Variant<K>` payloads and shared lazy codec tables. Static factory evaluation follows constructor captures, inherited accessor methods, component builders, and registration helpers. Generic codec factories get separate generated wire types for distinct schemas. Recursive codecs use lazy references; a dependency check rejects packets that reach an unfinished table. Complete tables cover filter masks, position paths, statistics, number formats, debug subscriptions, particles, recipe displays, slot displays, consume effects, and all 122 data-component types. Component predicates also use a recovered discriminator spanning two registries. The report lists every unresolved registry payload by numeric ID and name. Incomplete tables do not produce runtime codecs. Custom-payload tables contain the declared channel codecs and a bounded remaining-byte fallback. Unknown channel data stays available as `byte[]`; vanilla deliberately discards it. Known channel codecs retain their own limits. Their payloads do not inherit the fallback limit.

Map-update payloads use a recovered optional-record pattern where the leading unsigned byte also marks absence. Reader and writer field order must match. Remaining work concerns login query payloads, commands, merchant offers, equipment updates, player-info actions, movement deltas, and several specialized layouts. Add a lowering rule to `Analyzer`, add a wire primitive to `commons` when needed, and add behavior tests. A Java bytecode data-flow pass is a useful next step for layouts that Vineflower represents with temporary variables and branches.

## Generator CLI

The generator can reuse existing decompiled sources. Relative paths resolve from the repository root with the Gradle `run` task. External source mode records an unknown protocol version because it lacks verified jar metadata.

```sh
./gradlew :generator:run --args='--version 26.3 --sources /path/to/sources --output /path/to/generated'
./gradlew :generator:run --args='--version 26.3 --cache build/minecraft --output build/generated --strict'
```

`--strict` returns a failure when any registration lacks a codec. With the current 26.3 coverage, this failure is expected. The report is still written. The generator only replaces empty output directories or directories that contain its ownership marker.

## Validation

`./gradlew test` tests malformed wire inputs, field mismatches, generated Java compilation, and packet ID preservation. It round-trips three sample cases for each generated registration. Imperative codecs also receive mutations of valid seed bodies. Focused tests exercise conditional fields and bit flags. The compatibility test loads the verified server jar in an isolated class loader and initializes vanilla registries. This runs in tests only.

The current suite covers 735 generated round trips and 5,152 vanilla comparisons: 2,090 packet bodies, 1,521 dispatch payloads, 1,464 component patches, 29 recursive effect and sparse-enum values, and 48 named-codec, context, and payload boundary cases for 26.3. It also verifies every recovered registry discriminator and rejects source-invalid payload cases. It compares encoded bytes, then compares complete decoded wire values when unordered NBT entries change order. Sample cases do not prove every value or branch. Live client/server interoperability and other Minecraft versions are not validated. The coverage report includes exact input hashes and tool versions for reproducing the extraction.
