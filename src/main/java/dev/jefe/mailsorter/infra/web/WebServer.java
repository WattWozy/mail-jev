package dev.jefe.mailsorter.infra.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.jefe.mailsorter.application.exception.ConnectionFailedException;
import dev.jefe.mailsorter.application.exception.NotConnectedException;
import dev.jefe.mailsorter.application.port.in.ClassificationReport;
import dev.jefe.mailsorter.application.port.in.ClassifyCommand;
import dev.jefe.mailsorter.application.port.in.ClassifyUseCase;
import dev.jefe.mailsorter.application.port.in.ConnectUseCase;
import dev.jefe.mailsorter.domain.Action;
import dev.jefe.mailsorter.domain.MailboxConfig;
import dev.jefe.mailsorter.domain.WellKnownEmailProviders;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class WebServer implements AutoCloseable {

    private record Response(int status, Object body) {}

    private final HttpServer server;
    private final ConnectUseCase connect;
    private final ClassifyUseCase classify;
    private final ObjectMapper json = new ObjectMapper();

    public WebServer(String host, int port, ConnectUseCase connect, ClassifyUseCase classify) throws IOException {
        this.connect = connect;
        this.classify = classify;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/api/connect", post(this::handleConnect));
        server.createContext("/api/classify", post(this::handleClassify));
        server.createContext("/", this::serveUi);
    }

    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    @Override public void close() { server.stop(0); }

    private Response handleConnect(JsonNode body) {
        String provider = body.path("provider").asText("").toLowerCase();
        String email    = body.path("username").asText().trim();
        String secret   = body.path("secret").asText();

        MailboxConfig config = switch (provider) {
            case "gmx"     -> WellKnownEmailProviders.gmx(email, secret);
            case "gmail"   -> WellKnownEmailProviders.gmail(email, secret);
            case "outlook" -> WellKnownEmailProviders.outlook(email, secret);
            case "yahoo"   -> WellKnownEmailProviders.yahoo(email, secret);
            case "icloud"  -> WellKnownEmailProviders.icloud(email, secret);
            default        ->
                    MailboxConfig.builder()
                            .providerType("imap")
                            .host(body.path("host").asText().trim())
                            .port(body.path("port").asInt(993))
                            .username(email)
                            .secret(secret)
                            .option("ssl", body.path("ssl").asText("true"))
                            .build();
        };

        connect.execute(config);
        return new Response(200, Map.of("connected", true));
    }

    private Response handleClassify(JsonNode body) {
        int limit = body.path("limit").asInt(50);
        boolean apply = body.path("apply").asBoolean(false); // preview unless explicitly asked
        var command = apply ? ClassifyCommand.apply(limit) : ClassifyCommand.preview(limit);
        return new Response(200, toJson(classify.execute(command)));
    }

    private static Map<String, Object> toJson(ClassificationReport report) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Action a : Action.values()) counts.put(a.name(), report.countOf(a));
        List<Map<String, Object>> entries = report.entries().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("emailId", e.emailId());
            m.put("from", e.from());
            m.put("subject", e.subject());
            m.put("category", e.classification().category().name());
            m.put("confidence", e.classification().confidence());
            m.put("reason", e.classification().reason());
            m.put("action", e.action().name());
            return m;
        }).toList();
        return Map.of("dryRun", report.dryRun(), "counts", counts, "entries", entries);
    }

    private HttpHandler post(Function<JsonNode, Response> logic) {
        return exchange -> {
            try (exchange) {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    send(exchange, 405, "application/json", write(Map.of("error", "POST only")));
                    return;
                }
                Response response;
                try {
                    response = logic.apply(json.readTree(exchange.getRequestBody()));
                } catch (IllegalArgumentException | JsonProcessingException e) {
                    response = new Response(400, Map.of("error", String.valueOf(e.getMessage())));
                } catch (NotConnectedException e) {
                    response = new Response(409, Map.of("error", e.getMessage()));
                } catch (ConnectionFailedException e) {
                    response = new Response(502, Map.of("error", e.getMessage()));
                } catch (RuntimeException e) {
                    response = new Response(500, Map.of("error", "internal error"));
                }
                send(exchange, response.status(), "application/json", write(response.body()));
            }
        };
    }

    private void serveUi(HttpExchange exchange) throws IOException {
        try (exchange; InputStream page = WebServer.class.getResourceAsStream("/web/index.html")) {
            if (!"/".equals(exchange.getRequestURI().getPath()) || page == null) {
                send(exchange, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(exchange, 200, "text/html; charset=utf-8", page.readAllBytes());
        }
    }

    private byte[] write(Object value) {
        try {
            return json.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}