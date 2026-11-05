package org.stackanvil.packetforge.generator;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.ZipFile;

/** Fetches and decompiles artifacts without loading or running Minecraft classes. */
final class Artifacts {
    static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    static final String VINEFLOWER = "1.12.0";
    record Input(Path sources, int protocol, Map<String, String> provenance) {}
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build();

    Input prepare(String version, Path cache) throws Exception {
        Path directory = cache.resolve(version);
        Files.createDirectories(directory);
        Path metadata = directory.resolve("version.json");
        Path lock = directory.resolve("metadata-lock.json");
        if (!Files.exists(lock)) {
            JsonObject manifest = json(get(URI.create(MANIFEST)));
            JsonObject entry = null;
            for (var value : manifest.getAsJsonArray("versions")) {
                if (value.getAsJsonObject().get("id").getAsString().equals(version)) { entry = value.getAsJsonObject(); break; }
            }
            if (entry == null) throw new IllegalArgumentException("Minecraft version not found: " + version);
            fetch(URI.create(entry.get("url").getAsString()), metadata, "SHA-1", entry.get("sha1").getAsString());
            Files.writeString(lock, entry.toString());
        }
        JsonObject locked = json(Files.readString(lock));
        fetch(URI.create(locked.get("url").getAsString()), metadata, "SHA-1", locked.get("sha1").getAsString());
        JsonObject meta = json(Files.readString(metadata));
        if (!meta.get("id").getAsString().equals(version)) throw new IOException("Metadata version mismatch");
        JsonObject download = meta.getAsJsonObject("downloads").getAsJsonObject("server");
        Path bundle = directory.resolve("server-bundle.jar");
        fetch(URI.create(download.get("url").getAsString()), bundle, "SHA-1", download.get("sha1").getAsString());
        Path jar = directory.resolve("minecraft.jar");
        try (ZipFile zip = new ZipFile(bundle.toFile())) {
            String[] entry = new String(zip.getInputStream(zip.getEntry("META-INF/versions.list")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip().split("\\t");
            extract(zip, "META-INF/versions/" + entry[2], jar, entry[0]);
            Path libraries = directory.resolve("libraries");
            Files.createDirectories(libraries);
            String list = new String(zip.getInputStream(zip.getEntry("META-INF/libraries.list")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            for (String line : list.lines().filter(s -> !s.isBlank()).toList()) {
                String[] item = line.split("\\t");
                Path library = libraries.resolve(item[2]).normalize();
                if (!library.startsWith(libraries)) throw new IOException("Invalid library path");
                extract(zip, "META-INF/libraries/" + item[2], library, item[0]);
            }
        }
        int protocol;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            protocol = json(new String(zip.getInputStream(zip.getEntry("version.json")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).get("protocol_version").getAsInt();
        }
        String jarHash = digest(jar, "SHA-256");
        Path sources = directory.resolve("sources");
        Path marker = directory.resolve("decompiled.txt");
        String fingerprint = jarHash + " vineflower=" + VINEFLOWER;
        if (!Files.exists(marker) || !Files.readString(marker).equals(fingerprint)) {
            deleteTree(sources);
            Files.createDirectories(sources);
            Path log = directory.resolve("vineflower.log");
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            System.out.println("Decompiling Minecraft " + version + " with Vineflower " + VINEFLOWER + "; log: " + log);
            Process process = new ProcessBuilder(java, "-Xmx3g", "-cp", System.getProperty("java.class.path"),
                "org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler", "--folder", "--log-level=warn", "--thread-count=4", "--skip-extra-files=true", jar.toString(), sources.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (process.waitFor() != 0) throw new IOException("Vineflower failed. Read " + log);
            if (!Files.exists(sources.resolve("net/minecraft/network/protocol/Packet.java"))) throw new IOException("Vineflower did not produce expected named sources");
            Files.writeString(marker, fingerprint);
        }
        return new Input(sources, protocol, Map.of("version", version, "protocol", Integer.toString(protocol), "metadataSha1", locked.get("sha1").getAsString(),
            "serverSha1", download.get("sha1").getAsString(), "minecraftSha256", jarHash, "vineflower", VINEFLOWER));
    }
    private void extract(ZipFile zip, String entry, Path path, String sha256) throws Exception {
        if (Files.exists(path) && digest(path, "SHA-256").equals(sha256)) return;
        Files.createDirectories(path.getParent());
        try (var stream = zip.getInputStream(zip.getEntry(entry))) { Files.copy(stream, path, StandardCopyOption.REPLACE_EXISTING); }
        if (!digest(path, "SHA-256").equals(sha256)) throw new IOException("Checksum mismatch: " + entry);
    }
    private String get(URI uri) throws Exception {
        var response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + ": " + uri);
        return response.body();
    }
    private void fetch(URI uri, Path file, String algorithm, String hash) throws Exception {
        if (Files.exists(file) && digest(file, algorithm).equals(hash)) return;
        Path temporary = Files.createTempFile(file.getParent(), "download-", ".tmp");
        try {
            var response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).GET().build(), HttpResponse.BodyHandlers.ofFile(temporary));
            if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + ": " + uri);
            if (!digest(temporary, algorithm).equals(hash)) throw new IOException("Checksum mismatch: " + uri);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    static String digest(Path path, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (var input = Files.newInputStream(path)) { byte[] block = new byte[65536]; int length; while ((length = input.read(block)) != -1) digest.update(block, 0, length); }
        return HexFormat.of().formatHex(digest.digest());
    }
    static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var files = Files.walk(path)) { for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file); }
    }
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
}
