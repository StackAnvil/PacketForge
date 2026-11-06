# PacketForge

Use JDK 25 and the checked-in Gradle wrapper. Stay on the current branch.

All committed Java must use Palantir Java Format.
Run `./gradlew spotlessApply` to format handwritten Java sources with Palantir Java Format.
Run `./gradlew spotlessCheck` to check formatting. The build also runs this check.
Keep formatter targets restricted to source directories, excluding generated and decompiled files.
The generator formats emitted Java with Palantir Java Format before writing it.

Run `./gradlew :commons:test :generator:test` for runtime or analyzer changes.
Run `./gradlew :transport:test` for session or stream changes.
Run `./gradlew build` before publishing changes that affect generation.
The protocol tests download and decompile Minecraft on their first run.

Keep Minecraft jars and decompiled sources under the ignored build cache.
Never commit them. Generated sources also stay in build directories.

The generator must analyze source without loading Minecraft classes.
Only compatibility tests can load vanilla in their isolated class loader.
Treat unknown registration operations as fatal because they can shift packet IDs.
Treat unknown packet layouts as explicit diagnostics. Do not emit placeholder codecs.
Preserve the wire IDs of unsupported packets.

Validate source dependencies with `SchemaGraph` before public API lowering.
`PayloadApi` binds discriminator types. `ComponentReferences` binds component keys.
`ModelNames` assigns public model names. Component references require the full source registry.
`StatusWire` must validate status schemas before selecting the shared JSON codec.
Keep source identities, wire shapes, projection metadata, and string literals intact.
Construction helpers must delegate to canonical constructors to retain source validation.

Transport callbacks and stream mutations run on the session's event loop.
Keep inbound and outbound states independent. Write negotiation packets before applying new outbound settings.
Drain callback commands before decoding the next frame, including frames in the same TCP read.
Never block a network callback on a session future or event-loop shutdown.
Login handlers must authenticate asynchronously and validate the RSA challenge before enabling encryption.
Keep login and configuration acknowledgements in their old outbound state.
Authentication HTTP tests use local mocks. Never require live account credentials.
