# PacketForge

Use JDK 25 and the checked-in Gradle wrapper. Stay on the current branch.

All committed Java must use Palantir Java Format.
Run `./gradlew spotlessApply` to format handwritten Java sources with Palantir Java Format.
Run `./gradlew spotlessCheck` to check formatting. The build also runs this check.
Keep formatter targets restricted to source directories, excluding generated and decompiled files.
The generator formats emitted Java with Palantir Java Format before writing it.

Run `./gradlew :commons:test :generator:test` for runtime or analyzer changes.
Run `./gradlew build` before publishing changes that affect generation.
The protocol tests download and decompile Minecraft on their first run.

Keep Minecraft jars and decompiled sources under the ignored build cache.
Never commit them. Generated sources also stay in build directories.

The generator must analyze source without loading Minecraft classes.
Only compatibility tests can load vanilla in their isolated class loader.
Treat unknown registration operations as fatal because they can shift packet IDs.
Treat unknown packet layouts as explicit diagnostics. Do not emit placeholder codecs.
Preserve the wire IDs of unsupported packets.
