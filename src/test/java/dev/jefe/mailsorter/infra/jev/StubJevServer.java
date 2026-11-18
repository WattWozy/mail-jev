package dev.jefe.mailsorter.infra.jev;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

public final class StubJevServer implements AutoCloseable {
    private final HttpServer server;
    volatile int status = 200;
    volatile String responseBody = "{}";
    volatile String lastBody;
    volatile String lastAuth;
    public volatile Function<JsonNode, String> responder = body -> responseBody;
    private final ObjectMapper mapper = new ObjectMapper();

    public StubJevServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastBody = requestBody;
            lastAuth = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] bytes = responder.apply(mapper.readTree(requestBody)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
    }

    public URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"); }

    @Override public void close() { server.stop(0); }
}