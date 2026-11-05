# PacketForge

PacketForge generates a standalone Minecraft protocol library from the official server jar. It uses Vineflower and JavaParser to recover packet registrations and serialization operations.

This is an experimental generator, inspired by [MCProtocolLib](https://github.com/GeyserMC/MCProtocolLib). It is not a drop-in replacement. Minecraft 26.3 uses protocol version 777. The current generator emits codecs for **all 260 packet registrations**, with no unresolved registry payloads. This includes all 62 command-argument serializers, 122 data-component types, and 44 entity-metadata serializers.

## Build

Install JDK 25, then run:

```sh
./gradlew build
```

All committed Java uses Palantir Java Format. Run `./gradlew spotlessApply` to format handwritten Java sources, or `./gradlew spotlessCheck` to check them. Normal builds enforce formatting. The generator also formats its Java output with Palantir Java Format. Cached Minecraft sources are excluded from formatter targets.

The first build downloads the official server bundle and verifies its SHA-1 checksum against Mojang's version metadata. It extracts the server jar and bundled libraries, verifies their SHA-256 checksums, and decompiles the server jar and bundled Brigadier library with Vineflower 1.12.0. Brigadier supplies the string-argument mode enum. The report records its SHA-256 checksum. No Minecraft class runs during generation.

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

The analyzer lowers accepted codecs into a small intermediate representation. `ModelExtractor` selects specialized and general extraction rules. Rule attempts report matched, rejected, or not applicable results, and `ModelLayout` carries the extracted serialization separately from model identity and caching. Specialized packet failures are terminal; general fallback rules retain their rejection diagnostics. Command packet rules use structural `AstPattern` matching with identifier and type bindings. Local renaming, comments, and redundant parentheses do not change recognition. Operators, calls, argument order, statement counts, and wire-record layouts remain checked. Each field records its original type, generated type, decoder, encoder, and wire shape. The emitter creates standalone source files from that representation. `ProgramLowerer` translates serialization bodies, constructor delegation, local variables, branches, bit operations, and static helpers with primitive or buffer parameters. It preserves explicit numeric narrowing and widening. Independent read/write sequences can project game-object construction and normalization into physical wire fields. The report retains the source expressions for both directions. Conditional reads and mismatched operation counts remain unsupported by this projection. It omits game listeners and unrelated methods.

Supported patterns include direct buffer constructors and writers, record constructor delegation, `Packet.codec`, `StreamCodec.unit`, and `StreamCodec.composite`, wrapper mappings, and constructor/writer pairs for nested data. Supported validation guards remain in generated constructors, including compact record constructors. Shared wire codecs cover primitives, variable integers, bounded UTF-8, UUIDs, identifiers, byte arrays, packed block positions, nullable values, optional values, maps, sets, fixed-size lists, and ordinary lists. UTF-8 decoding uses vanilla's replacement behavior for malformed byte sequences. Movement vectors use standalone value types and shared codecs for their full and compact representations. Relative entity movement uses `MovementDelta.Linear` or timed `MovementDelta.Stepped` values. The outer packet supplies the step count. Equipment updates use ordered `Pair<EquipmentSlot, ItemStack>` entries and retain their continuation-bit encoding. Packed section changes expose position and block-state ID arrays, with decoded allocation sizes bounded by the available input bytes. The analyzer also follows supported codec aliases and nested value codecs.

For linear layouts, the analyzer checks field order and matching read/write operations. For imperative layouts, it translates the control flow and compares wire operation kinds. It recovers ordinal enums, continuous and sparse enum ID maps, named enum values and their fallback rules, matching enum switch expressions, and enum-held constructor decoders with virtual payload writers. The latter recovers all six boss-event operations, including the payload-free remove operation. Registry values use numeric wire IDs or `RegistryValue<T>` for references with an inline alternative. `HolderSet` represents registry tags and explicit ID lists. `ComponentPatch` holds added component wire values and removed component IDs. `ItemStack` holds its count, item ID, and component patch. Shared codecs support both ordinary and length-delimited component payloads. Entity metadata uses indexed `MetadataEntry` values and a terminating byte. The analyzer recovers all 44 serializer IDs from the explicit registration sequence. NBT-backed fields use `NbtTag`, and JSON-backed fields use Gson `JsonElement`. Applications interpret the registry IDs and NBT schemas. Generated records describe wire values, and the report records their original types and factories. Domain-only `StreamCodec.map` transformations retain the underlying wire type and record the source mapping as projection metadata.

Unknown packet layouts remain in `GeneratedProtocol.DESCRIPTORS` and the JSON report. They have no registered codec. Their original IDs remain reserved. Unknown registration operations fail generation because they can shift every later ID.

Enum and registry dispatch tables produce `Variant<K>` payloads and shared lazy codec tables. Static factory evaluation follows constructor captures, inherited accessor methods, component builders, and registration helpers. Generic codec factories get separate generated wire types for distinct schemas. Recursive codecs use lazy references. `SchemaGraph` links model and dispatch identities from Java syntax nodes in the intermediate representation. Analysis and emission use the same graph to check dependencies and retain reachable schemas, including context branches, validation guards, and helper methods. String values and diagnostic metadata do not create dependencies. A packet that reaches an unfinished table is rejected. Complete tables cover filter masks, position paths, statistics, number formats, debug subscriptions, particles, recipe displays, slot displays, consume effects, and all 122 data-component types. Component predicates also use a recovered discriminator spanning two registries. The report lists every unresolved registry payload by numeric ID and name. Incomplete tables do not produce runtime codecs. Custom-payload tables contain the declared channel codecs and a bounded remaining-byte fallback. Unknown channel data stays available as `byte[]`; vanilla deliberately discards it. Known channel codecs retain their own limits. Their payloads do not inherit the fallback limit.

Map-update payloads use a recovered optional-record pattern where the leading unsigned byte also marks absence. Reader and writer field order must match. Tracked waypoints use a shared identifier and icon header followed by one of four recovered subclass payloads. The analyzer checks superclass forwarding and the discriminator of each subclass before separating its contents into a generated record. Advancement updates include display flags and optional background textures. Merchant offers retain their serialized fields, including the exhaustion flag separately from the use count; callers should supply canonical values when comparing with vanilla, which normalizes exhausted offers. For new source patterns, add a lowering rule to the generator, add a wire primitive to `commons` when needed, and add behavior tests.

Login queries expose their transaction ID, channel where present, and custom `data` bytes. Vanilla discards unknown query data; the generated library retains it. For `ServerboundCustomQueryAnswerPacket`, `data` includes the nullable presence byte: `{0}` represents no answer, and `{1, ...}` represents an answer followed by its custom bytes. The source reader consumes this whole remainder without interpreting that byte, so the generated decoder also retains empty or noncanonical remainders. Both query readers limit the remainder to 1,048,576 bytes. Their writers retain the source's unbounded output behavior. The report marks this projection explicitly.

Player-info updates expose a set of generated `Action` enum values and a list of `ActionEntry<Action>` values. Each entry holds its UUID and a map from actions to generated payload records. The generator extracts all eight reader/writer lambda pairs from the source enum. A fixed mask selects which payloads appear for every entry. Shared codecs write them in enum order, require each selected payload, and ignore values for unselected actions. Tests compare all 256 masks with two player entries and three payload variants against vanilla.

Command trees expose `CommandNode` values with flags, child IDs, an optional redirect, and a literal or typed argument stub. Argument values use `Variant<Integer>` with the recovered registry ID and a generated payload record, or `Unit.INSTANCE` for a serializer with no wire fields. Numeric serializers retain conditional bounds; string modes use the recovered Brigadier enum. The packet constructor checks child and redirect cycles as separate graphs. As in vanilla, references outside the node set do not affect those checks. An unknown argument ID produces an absent stub and consumes no suggestion field. Re-encoding that source-normalized node omits its discarded argument header, so arbitrary unknown-ID inputs need not round-trip byte for byte.

## Generator CLI

The generator can reuse existing decompiled sources. Relative paths resolve from the repository root with the Gradle `run` task. External source mode records an unknown protocol version because it lacks verified jar metadata. The supplied source tree must include the required Brigadier declarations as well as Minecraft sources.

```sh
./gradlew :generator:run --args='--version 26.3 --sources /path/to/sources --output /path/to/generated'
./gradlew :generator:run --args='--version 26.3 --cache build/minecraft --output build/generated --strict'
```

`--strict` returns a failure when any registration lacks a codec. Normal protocol builds enable this check. The report is written before a coverage failure, so new versions can be investigated with the CLI without `--strict`. The generator only replaces empty output directories or directories that contain its ownership marker.

## Validation

`./gradlew test` tests malformed wire inputs, field mismatches, generated Java compilation, and packet ID preservation. It round-trips three sample cases for each generated registration. Imperative codecs also receive mutations of valid seed bodies. Focused tests exercise conditional fields and bit flags. The compatibility test loads the verified server jar in an isolated class loader and initializes vanilla registries. This runs in tests only.

The current suite covers 780 generated round trips and 8,083 vanilla comparisons: 3,705 packet bodies, 1,761 dispatch payloads, 1,464 component patches, 32 nested-value and sparse-enum cases, and 1,121 named-codec, context, command-flag, graph, and payload boundary cases for 26.3. It also compares all 260 packet IDs, directions, states, and names with vanilla protocol details, verifies every recovered registry discriminator, and rejects source-invalid payload cases. Command tests cover all 256 node-flag bytes and all 256 bound-flag bytes for each numeric argument type. It compares encoded bytes, then compares complete decoded wire values when unordered NBT entries change order. Sample cases do not prove every value or branch. Live client/server interoperability and other Minecraft versions are not validated. The coverage report includes exact input hashes and tool versions for reproducing the extraction.
