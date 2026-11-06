package org.stackanvil.packetforge.transport.login;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.stackanvil.packetforge.commons.ProfileProperty;
import org.stackanvil.packetforge.protocol.type.external.com.mojang.authlib.GameProfile;

/** Asynchronous Minecraft session authentication using the 26.3 discovery service. */
public final class MojangSessionService implements ServerAuthenticator, AutoCloseable {
    private static final URI DISCOVERY = URI.create("https://discovery.minecraftservices.com/minecraft/client");
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final long DISCOVERY_TTL = Duration.ofHours(24).toNanos();
    private final HttpClient http;
    private final URI discoveryUri;
    private final boolean ownsClient;
    private CompletableFuture<Endpoints> discovery;
    private long fetchedAt;
    private boolean closed;

    private record Endpoints(URI join, URI verify) {}

    public MojangSessionService() {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                DISCOVERY,
                true);
    }

    /** Uses a caller-owned client. HTTP is accepted only for loopback service tests. */
    public MojangSessionService(HttpClient http, URI discoveryUri) {
        this(http, discoveryUri, false);
    }

    private MojangSessionService(HttpClient http, URI discoveryUri, boolean ownsClient) {
        this.http = Objects.requireNonNull(http);
        if (http.followRedirects() != HttpClient.Redirect.NEVER)
            throw new IllegalArgumentException("Session authentication requires redirects to be disabled");
        this.discoveryUri = endpoint(Objects.requireNonNull(discoveryUri));
        this.ownsClient = ownsClient;
    }

    /** Binds an existing Minecraft access token; acquiring that token belongs to the application. */
    public ClientAuthenticator authenticator(String accessToken) {
        Objects.requireNonNull(accessToken);
        if (accessToken.isBlank()) throw new IllegalArgumentException("Empty Minecraft access token");
        return (profile, hash) -> joinServer(profile, accessToken, hash);
    }

    public CompletableFuture<Void> joinServer(UUID profile, String accessToken, String serverHash) {
        Objects.requireNonNull(profile);
        Objects.requireNonNull(accessToken);
        Objects.requireNonNull(serverHash);
        var body = new JsonObject();
        body.addProperty("accessToken", accessToken);
        body.addProperty("selectedProfile", profile.toString().replace("-", ""));
        body.addProperty("serverId", serverHash);
        return endpoints()
                .thenCompose(endpoints -> request(HttpRequest.newBuilder(endpoints.join())
                        .header("Content-Type", "application/json; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))))
                .thenAccept(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300)
                        throw status("join", response.statusCode());
                });
    }

    @Override
    public CompletableFuture<GameProfile> hasJoinedServer(String username, String serverHash, InetAddress address) {
        LoginCrypto.validateUsername(username);
        Objects.requireNonNull(serverHash);
        String query = "username=" + escape(username) + "&serverId=" + escape(serverHash)
                + (address == null ? "" : "&ip=" + escape(address.getHostAddress()));
        return endpoints()
                .thenCompose(endpoints -> {
                    String existing = endpoints.verify().getRawQuery();
                    String separator = existing == null ? "?" : existing.isEmpty() ? "" : "&";
                    URI uri = URI.create(endpoints.verify().toASCIIString() + separator + query);
                    return request(HttpRequest.newBuilder(uri).GET());
                })
                .thenApply(response -> {
                    int status = response.statusCode();
                    if (status == 204 || status == 403 || status == 404) return null;
                    if (status != 200) throw status("verify", status);
                    JsonObject json = parse(response.body());
                    if (!json.has("id") || json.get("id").isJsonNull()) return null;
                    UUID id = uuid(json.get("id").getAsString());
                    return new GameProfile(id, username, properties(json.get("properties")));
                });
    }

    private synchronized CompletableFuture<Endpoints> endpoints() {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Session service is closed"));
        if (discovery != null && !discovery.isCompletedExceptionally() && System.nanoTime() - fetchedAt < DISCOVERY_TTL)
            return discovery;
        fetchedAt = System.nanoTime();
        discovery = request(HttpRequest.newBuilder(discoveryUri).GET()).thenApply(response -> {
            if (response.statusCode() != 200) throw status("discovery", response.statusCode());
            JsonObject root = parse(response.body());
            if ("offline".equals(root.get("environment").getAsString()))
                throw new CompletionException(new IOException("Minecraft session services are unavailable"));
            JsonObject endpoints =
                    root.getAsJsonObject("discovery").getAsJsonObject("session").getAsJsonObject("endpoints");
            return new Endpoints(
                    endpoint(URI.create(
                            endpoints.getAsJsonObject("join").get("uri").getAsString())),
                    endpoint(URI.create(
                            endpoints.getAsJsonObject("verify").get("uri").getAsString())));
        });
        return discovery;
    }

    private CompletableFuture<HttpResponse<String>> request(HttpRequest.Builder builder) {
        try {
            return http.sendAsync(
                    builder.timeout(TIMEOUT)
                            .header("Accept", "application/json")
                            .build(),
                    HttpResponse.BodyHandlers.limiting(
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), 1024 * 1024));
        } catch (RuntimeException invalid) {
            return CompletableFuture.failedFuture(invalid);
        }
    }

    private static JsonObject parse(String json) {
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException invalid) {
            throw new CompletionException(new IOException("Invalid session service response"));
        }
    }

    private static List<ProfileProperty> properties(JsonElement json) {
        var result = new ArrayList<ProfileProperty>();
        if (json instanceof JsonObject object) {
            for (var entry : object.entrySet()) {
                if (!entry.getValue().isJsonArray()) continue;
                for (var value : entry.getValue().getAsJsonArray())
                    result.add(new ProfileProperty(entry.getKey(), value.getAsString(), null));
            }
        } else if (json != null && json.isJsonArray()) {
            for (var element : json.getAsJsonArray()) {
                if (!(element instanceof JsonObject property)) continue;
                String signature =
                        property.has("signature") && !property.get("signature").isJsonNull()
                                ? property.get("signature").getAsString()
                                : null;
                result.add(new ProfileProperty(
                        property.get("name").getAsString(),
                        property.get("value").getAsString(),
                        signature));
            }
        }
        return List.copyOf(result);
    }

    private static CompletionException status(String operation, int status) {
        // Do not include response bodies or request JSON: they can contain account credentials.
        return new CompletionException(
                new IOException("Minecraft session " + operation + " failed (HTTP " + status + ")"));
    }

    private static URI endpoint(URI uri) {
        String host = uri.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        if (host == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || !("https".equalsIgnoreCase(uri.getScheme())
                        || (loopback && "http".equalsIgnoreCase(uri.getScheme()))))
            throw new IllegalArgumentException("Session endpoints require HTTPS or loopback HTTP");
        return uri;
    }

    private static UUID uuid(String value) {
        if (value.matches("[0-9a-fA-F]{32}"))
            value = value.substring(0, 8) + "-" + value.substring(8, 12) + "-" + value.substring(12, 16) + "-"
                    + value.substring(16, 20) + "-" + value.substring(20);
        return UUID.fromString(value);
    }

    private static String escape(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Shuts down the owned HTTP client without waiting on a network callback. */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (discovery != null) discovery.cancel(true);
        if (ownsClient) http.shutdownNow();
    }
}
