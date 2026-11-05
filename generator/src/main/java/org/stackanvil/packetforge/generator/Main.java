package org.stackanvil.packetforge.generator;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new HashMap<>();
        boolean strict = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--help")) {
                System.out.println(
                        "PacketForge --version <id> --cache <directory> --output <directory> [--sources <decompiled directory>] [--strict]\n--strict fails when any registered packet lacks a generated codec.");
                return;
            }
            if (args[i].equals("--strict")) {
                strict = true;
                continue;
            }
            if (!java.util.Set.of("--version", "--cache", "--output", "--sources").contains(args[i])
                    || i + 1 == args.length)
                throw new IllegalArgumentException("Unknown or incomplete argument: " + args[i]);
            if (options.put(args[i], args[++i]) != null)
                throw new IllegalArgumentException("Duplicate option: " + args[i - 1]);
        }
        String version = options.getOrDefault("--version", "26.3");
        if (!version.matches("[A-Za-z0-9._-]+") || version.equals(".") || version.equals(".."))
            throw new IllegalArgumentException("Invalid version ID");
        Path output =
                Path.of(options.getOrDefault("--output", "build/generated"))
                        .toAbsolutePath()
                        .normalize();
        Path cache =
                Path.of(options.getOrDefault("--cache", "build/minecraft"))
                        .toAbsolutePath()
                        .normalize();
        Artifacts.Input input;
        if (options.containsKey("--sources")) {
            input =
                    new Artifacts.Input(
                            Path.of(options.get("--sources")).toAbsolutePath().normalize(),
                            -1,
                            Map.of(
                                    "version",
                                    version,
                                    "mode",
                                    "external-sources",
                                    "protocol",
                                    "unknown"));
        } else {
            input = new Artifacts().prepare(version, cache);
        }
        if (output.startsWith(input.sources())
                || input.sources().startsWith(output)
                || cache.startsWith(output)
                || output.equals(Path.of("").toAbsolutePath().normalize()))
            throw new IllegalArgumentException(
                    "Output must be separate from sources, cache, and working directory");
        Analyzer analyzer = new Analyzer(input.sources());
        var registrations = analyzer.analyze();
        var report =
                new Emitter()
                        .emit(
                                output,
                                input.provenance(),
                                input.protocol(),
                                registrations,
                                analyzer.models,
                                java.util.List.copyOf(analyzer.dispatches.values()),
                                analyzer.registryGaps);
        if (strict && report.generatedRegistrations() != report.totalRegistrations())
            throw new IllegalStateException(
                    "Unsupported packet codecs remain. Read resources/packetforge/protocol.json in "
                            + output);
    }
}
