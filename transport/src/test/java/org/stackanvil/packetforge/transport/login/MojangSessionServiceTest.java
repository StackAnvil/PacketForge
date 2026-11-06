package org.stackanvil.packetforge.transport.login;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.stackanvil.packetforge.commons.ProfileProperty;

class MojangSessionServiceTest {
    static final UUID PROFILE =
            LoginCrypto.offlineProfile(LoginIntegrationTest.USERNAME).id();
    static final String TOKEN = "fixture-token";

    static <T> T await(CompletableFuture<T> future) throws Exception {
        return LoginIntegrationTest.await(future);
    }

    static final class Mock implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final HttpClient client = HttpClient.newHttpClient();
        final AtomicInteger discoveries = new AtomicInteger();
        final AtomicInteger joinStatus = new AtomicInteger(204);
        final AtomicInteger verifyStatus = new AtomicInteger(200);
        final AtomicReference<String> discoveryBody = new AtomicReference<>();
        final AtomicReference<String> verifyBody = new AtomicReference<>(
                "{\"id\":\"" + PROFILE.toString().replace("-", "")
                        + "\",\"properties\":[{\"name\":\"textures\",\"value\":\"value\",\"signature\":\"signature\"}]}");
        final AtomicReference<JsonObject> joined = new AtomicReference<>();
        final AtomicReference<Map<String, String>> verified = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final URI base;
        final MojangSessionService service;

        Mock() throws Exception {
            base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            server.createContext(
                    "/discovery",
                    exchange -> handle(exchange, () -> {
                        discoveries.incrementAndGet();
                        String json = discoveryBody.get();
                        respond(
                                exchange,
                                200,
                                json == null
                                        ? "{\"environment\":\"prod\",\"discovery\":{\"session\":{\"endpoints\":{\"join\":{\"uri\":\""
                                                + base + "/join\"},\"verify\":{\"uri\":\"" + base
                                                + "/verify?application=packetforge\"}}}}}"
                                        : json);
                    }));
            server.createContext(
                    "/join",
                    exchange -> handle(exchange, () -> {
                        assertEquals("POST", exchange.getRequestMethod());
                        assertTrue(exchange.getRequestHeaders()
                                .getFirst("Content-Type")
                                .startsWith("application/json"));
                        joined.set(JsonParser.parseString(
                                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                                .getAsJsonObject());
                        respond(exchange, joinStatus.get(), TOKEN);
                    }));
            server.createContext(
                    "/verify",
                    exchange -> handle(exchange, () -> {
                        assertEquals("GET", exchange.getRequestMethod());
                        var query = new java.util.HashMap<String, String>();
                        for (String entry :
                                exchange.getRequestURI().getRawQuery().split("&")) {
                            String[] parts = entry.split("=", 2);
                            query.put(
                                    URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
                        }
                        verified.set(Map.copyOf(query));
                        respond(exchange, verifyStatus.get(), verifyBody.get());
                    }));
            server.start();
            service = new MojangSessionService(client, base.resolve("/discovery"));
        }

        @FunctionalInterface
        interface Operation {
            void run() throws Exception;
        }

        void handle(HttpExchange exchange, Operation operation) throws IOException {
            try {
                operation.run();
            } catch (Throwable invalid) {
                failure.set(invalid);
                respond(exchange, 500, "mock failure");
            } finally {
                exchange.close();
            }
        }

        static void respond(HttpExchange exchange, int status, String text) throws IOException {
            if (status == 204) {
                exchange.sendResponseHeaders(status, -1);
                return;
            }
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        @Override
        public void close() {
            service.close();
            server.stop(0);
            client.close();
            if (failure.get() != null) throw new AssertionError("Mock session service failed", failure.get());
        }
    }

    @Test
    void discoveryRoutesJoinAndVerifyAndIsSharedAcrossRequests() throws Exception {
        try (var mock = new Mock()) {
            var joined = new ArrayList<CompletableFuture<Void>>();
            for (int i = 0; i < 10; i++)
                joined.add(mock.service
                        .authenticator(TOKEN)
                        .joinServer(PROFILE, "-abc")
                        .toCompletableFuture());
            await(CompletableFuture.allOf(joined.toArray(CompletableFuture[]::new)));
            var profile = await(mock.service.hasJoinedServer("Player+&?", "-abc", InetAddress.getByName("127.0.0.1")));
            assertEquals(PROFILE, profile.id());
            assertEquals("Player+&?", profile.name());
            assertEquals(
                    java.util.List.of(new ProfileProperty("textures", "value", "signature")), profile.properties());
            assertEquals(TOKEN, mock.joined.get().get("accessToken").getAsString());
            assertEquals(
                    PROFILE.toString().replace("-", ""),
                    mock.joined.get().get("selectedProfile").getAsString());
            assertEquals("-abc", mock.joined.get().get("serverId").getAsString());
            assertEquals(
                    Map.of(
                            "application",
                            "packetforge",
                            "username",
                            "Player+&?",
                            "serverId",
                            "-abc",
                            "ip",
                            "127.0.0.1"),
                    mock.verified.get());
            assertEquals(1, mock.discoveries.get());
        }
    }

    @Test
    void realLoginUsesTheHttpAuthenticatorAndVerifier() throws Exception {
        try (var mock = new Mock();
                var pair = new LoginIntegrationTest.Pair(
                        ServerLogin.online(LoginIntegrationTest.KEYS, mock.service),
                        builder -> builder.authenticator(mock.service.authenticator(TOKEN)))) {
            var profile = await(pair.serverLogin.authenticated());
            assertEquals(PROFILE, profile.id());
            assertEquals(profile, await(pair.clientLogin.authenticated()));
            assertEquals(
                    mock.joined.get().get("serverId").getAsString(),
                    mock.verified.get().get("serverId"));
            assertFalse(mock.verified.get().containsKey("ip"));
            await(pair.serverLogin.finishConfiguration());
            await(pair.clientLogin.ready());
        }
    }

    @Test
    void unavailableAndRejectedResponsesRemainDistinctAndDoNotExposeTokens() throws Exception {
        try (var mock = new Mock()) {
            mock.joinStatus.set(403);
            var failed = assertThrows(
                    ExecutionException.class, () -> await(mock.service.joinServer(PROFILE, TOKEN, "hash")));
            assertTrue(failed.getCause().getMessage().contains("403"));
            assertFalse(failed.getCause().toString().contains(TOKEN));
            for (int status : new int[] {204, 403, 404}) {
                mock.verifyStatus.set(status);
                assertNull(await(mock.service.hasJoinedServer("Player", "hash", null)));
            }
            mock.verifyStatus.set(503);
            assertThrows(ExecutionException.class, () -> await(mock.service.hasJoinedServer("Player", "hash", null)));
            mock.verifyStatus.set(200);
            mock.verifyBody.set("{}");
            assertNull(await(mock.service.hasJoinedServer("Player", "hash", null)));
        }
    }

    @Test
    void failedDiscoveryIsRetriedAndMalformedEndpointsAreNotUsed() throws Exception {
        try (var mock = new Mock()) {
            mock.discoveryBody.set("{\"environment\":\"offline\"}");
            assertThrows(ExecutionException.class, () -> await(mock.service.joinServer(PROFILE, TOKEN, "hash")));
            assertNull(mock.joined.get());
            mock.discoveryBody.set(
                    "{\"environment\":\"prod\",\"discovery\":{\"session\":{\"endpoints\":{\"join\":{\"uri\":\"file:///tmp/forbidden\"},\"verify\":{\"uri\":\""
                            + mock.base + "/verify\"}}}}}");
            assertThrows(ExecutionException.class, () -> await(mock.service.joinServer(PROFILE, TOKEN, "hash")));
            assertNull(mock.joined.get());
            mock.discoveryBody.set(null);
            await(mock.service.joinServer(PROFILE, TOKEN, "hash"));
            assertEquals(3, mock.discoveries.get());
        }
    }

    @Test
    void malformedAndOversizedResponsesFailAndMissingPropertiesDefaultToEmpty() throws Exception {
        try (var mock = new Mock()) {
            for (String body : new String[] {"not JSON", "[]", "{\"id\":\"invalid\"}", "x".repeat(1024 * 1024 + 1)}) {
                mock.verifyBody.set(body);
                assertThrows(
                        ExecutionException.class, () -> await(mock.service.hasJoinedServer("Player", "hash", null)));
            }
            mock.verifyBody.set("{\"id\":\"" + PROFILE + "\"}");
            assertTrue(await(mock.service.hasJoinedServer("Player", "hash", null))
                    .properties()
                    .isEmpty());
            mock.verifyBody.set(
                    "{\"id\":\"" + PROFILE + "\",\"properties\":{\"textures\":[\"first\",\"second\"],\"ignored\":0}}");
            assertEquals(
                    java.util.List.of(
                            new ProfileProperty("textures", "first", null),
                            new ProfileProperty("textures", "second", null)),
                    await(mock.service.hasJoinedServer("Player", "hash", null)).properties());
        }
    }

    @Test
    void closingTheServiceDoesNotCloseACallerOwnedHttpClient() throws Exception {
        try (var mock = new Mock()) {
            mock.service.close();
            assertThrows(ExecutionException.class, () -> await(mock.service.joinServer(PROFILE, TOKEN, "hash")));
            assertEquals(
                    200,
                    await(mock.client.sendAsync(
                                    HttpRequest.newBuilder(mock.base.resolve("/discovery"))
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.discarding()))
                            .statusCode());
        }
        try (var client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new MojangSessionService(client, URI.create("https://example.com")));
        }
    }
}
