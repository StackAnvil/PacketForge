# PacketForge

PacketForge generates a standalone Minecraft protocol library from the official server jar. It uses Vineflower and JavaParser to recover packet registrations and serialization operations.

This is an experimental generator, inspired by [MCProtocolLib](https://github.com/GeyserMC/MCProtocolLib). It is not a drop-in replacement. Minecraft 26.3 uses protocol version 777. The current generator emits codecs for **128 of 260 packet registrations**. It reports the other 132 with their IDs, source locations, and rejection reasons.

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

The generated protocol depends only on `commons` and Netty. Minecraft, Vineflower, and JavaParser are absent from its runtime dependencies. Packet records expose a `CODEC` and implement `Packet.serialize(ByteBuf)`.

```java
import org.stackanvil.packetforge.protocol.GeneratedProtocol;
import org.stackanvil.packetforge.protocol.packet.common.ClientboundKeepAlivePacket;

var packet = new ClientboundKeepAlivePacket(123L);
packet.serialize(output); // Packet body, without its numeric ID.

var registry = GeneratedProtocol.registry();
var received = registry.decode(state, flow, completePacketBodyWithId);
```

`state` and `flow` use `PacketRegistry.State` and `PacketRegistry.Flow`. Supply one complete packet body to `registry.decode`. The registry reads the numeric ID and rejects trailing bytes. Framing, sessions, compression, encryption, authentication, and bundle assembly are outside this project.

## Extraction

`SourceIndex` resolves explicit imports and nested declarations without loading game classes. `Analyzer` walks each fluent protocol builder from its receiver outward. Registration order determines numeric IDs. A bundle delimiter consumes a wire ID. The enclosing builder determines protocol state and direction.

The analyzer lowers accepted codecs into a small intermediate representation. Each field records its original type, generated type, decoder, encoder, and wire shape. The emitter creates new source files from that representation. It does not copy packet methods or listener code.

Supported patterns include direct buffer constructors and writers, record constructor delegation, `Packet.codec`, `StreamCodec.unit`, and record-based `StreamCodec.composite`. Shared wire codecs cover primitives, variable integers, bounded UTF-8, UUIDs, identifiers, byte arrays, packed block positions, nullable values, optional values, and lists. The analyzer also follows supported codec aliases and nested value codecs.

The analyzer checks field order and matching read/write operations. It recovers ordinal enums, continuous enum ID maps and their fallback rules, and matching enum switch expressions. Registry values use numeric wire IDs in the generated API. The application must supply meaning for those IDs.

Unknown packet layouts remain in `GeneratedProtocol.DESCRIPTORS` and the JSON report. They have no registered codec. Their original IDs remain reserved. Unknown registration operations fail generation because they can shift every later ID.

Most remaining work concerns registry-backed NBT codecs, data components, item stacks, polymorphic payloads, inherited layouts, and control flow such as bit packing. Add a lowering rule to `Analyzer`, add a wire primitive to `commons` when needed, and add behavior tests. A Java bytecode data-flow pass is a useful next step for layouts that Vineflower represents with temporary variables and branches.

## Generator CLI

The generator can reuse existing decompiled sources. Relative paths resolve from the repository root with the Gradle `run` task. External source mode records an unknown protocol version because it lacks verified jar metadata.

```sh
./gradlew :generator:run --args='--version 26.3 --sources /path/to/sources --output /path/to/generated'
./gradlew :generator:run --args='--version 26.3 --cache build/minecraft --output build/generated --strict'
```

`--strict` returns a failure when any registration lacks a codec. With the current 26.3 coverage, this failure is expected. The report is still written. The generator only replaces empty output directories or directories that contain its ownership marker.

## Validation

`./gradlew test` tests malformed wire inputs, field mismatches, generated Java compilation, and packet ID preservation. It also round-trips three samples for every generated registration. The compatibility test decodes and re-encodes those bodies with vanilla, plus 17 focused samples. It loads the verified server jar and its dependencies in an isolated class loader and initializes vanilla registries. This runs in tests only.

These tests cover 384 generated round trips and 401 vanilla comparisons for 26.3. They do not prove correctness for every value or provide a live client/server interoperability test. Other Minecraft versions are not validated. The coverage report includes exact input hashes and tool versions for reproducing the extraction.
