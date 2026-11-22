package dev.jefe.mailsorter.infra.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.jefe.mailsorter.application.exception.AccountNotFoundException;
import dev.jefe.mailsorter.application.exception.ConnectionFailedException;
import dev.jefe.mailsorter.application.exception.NotConnectedException;
import dev.jefe.mailsorter.application.exception.PlanNotFoundException;
import dev.jefe.mailsorter.application.port.in.*;
import dev.jefe.mailsorter.domain.Action;
import dev.jefe.mailsorter.domain.EmailContent;
import dev.jefe.mailsorter.domain.config.MailboxConfig;
import dev.jefe.mailsorter.domain.WellKnownEmailProviders;
import dev.jefe.mailsorter.infra.logging.AppLog;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class WebServer implements AutoCloseable {

    private record Response(int status, Object body) {}

    private final HttpServer server;
    private final ConnectUseCase connectService;
    private final ListAccountsUseCase listAccounts;
    private final ClassifyUseCase classify;
    private final ApplyPlanUseCase apply;
    private final GetEmailUseCase getEmail;
    private final ObjectMapper json = new ObjectMapper();

    public WebServer(String host, int port,
                     ConnectUseCase connect,
                     ListAccountsUseCase listAccounts,
                     ClassifyUseCase classify,
                     ApplyPlanUseCase apply,
                     GetEmailUseCase getEmail) throws IOException {
        this.connectService = connect;
        this.listAccounts = listAccounts;
        this.classify = classify;
        this.apply = apply;
        this.getEmail = getEmail;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/api/connect", post(this::handleConnect));
        server.createContext("/api/accounts", get(this::handleListAccounts));
        server.createContext("/api/accounts/connect", post(this::handleConnectSaved));
        server.createContext("/api/classify", post(this::handleClassify));
        server.createContext("/api/apply", post(this::handleApply));
        server.createContext("/api/email", post(this::handleEmail));
        server.createContext("/", this::serveUi);
    }

    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    @Override public void close() { server.stop(0); }

    // GET /accounts
    private Response handleListAccounts() {
        return new Response(200, listAccounts.list());       // id, service, username, status: never a secret
    }

    private Response handleConnectSaved(JsonNode body) {
        try {
            listAccounts.connectSaved(body.path("id").asText(""));
            return new Response(200, Map.of("connected", true));
        } catch (AccountNotFoundException e) {
            return new Response(404, Map.of("error", "Unknown account"));
        }
        // ConnectionFailedException → 502, through the same mapping your /connect handler already uses
    }

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

        connectService.execute(config);
        return new Response(200, Map.of("connected", true));
    }

    /** Always produces a plan – never mutates the mailbox. */
    private Response handleClassify(JsonNode body) {
        int limit = body.path("limit").asInt(50);
        Plan plan = classify.execute(new ClassifyCommand(limit));
        return new Response(200, toJson(plan));
    }

    /**
     * Applies a previously created plan.
     * Body: { "planId": "...", "overrides": { "emailId": "ARCHIVE", ... } }
     */
    private Response handleApply(JsonNode body) {
        String planId = body.path("planId").asText(null);
        if (planId == null || planId.isBlank()) {
            return new Response(400, Map.of("error", "planId is required"));
        }

        Map<String, Action> overrides = Map.of();
        JsonNode overridesNode = body.path("overrides");
        if (overridesNode.isObject()) {
            overrides = overridesNode.properties().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> Action.valueOf(e.getValue().asText())
                    ));
        }

        ApplyResult result = apply.execute(new ApplyCommand(planId, overrides));
        return new Response(200, toJson(result));
    }

    private Response handleEmail(JsonNode body) {
        String emailId = body.path("emailId").asText(null);
        if (emailId == null || emailId.isBlank()) {
            return new Response(400, Map.of("error", "emailId is required"));
        }

        try {
            EmailContent content = getEmail.execute(emailId);
            return new Response(200, toJson(content));
        } catch (Exception e) {
            return new Response(404, Map.of("error", e.getMessage()));
        }
    }

    private static Map<String, Object> toJson(EmailContent content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("emailId", content.emailId());
        m.put("from", content.from());
        m.put("subject", content.subject());
        m.put("date", content.date());       // or whatever the accessor is called
        m.put("textBody", content.textBody());
        return m;
    }

    private static Map<String, Object> toJson(Plan plan) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Action a : Action.values()) {
            counts.put(a.name(), plan.countOf(a));
        }
        List<Map<String, Object>> entries = plan.entries().stream().map(e -> {
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
        return Map.of(
                "planId", plan.id(),
                "counts", counts,
                "entries", entries
        );
    }

    private static Map<String, Object> toJson(ApplyResult result) {
        Map<String, Long> applied = new LinkedHashMap<>();
        result.applied().forEach((action, count) -> applied.put(action.name(), count));

        List<Map<String, String>> failures = result.failures().stream()
                .map(f -> Map.of("emailId", f.emailId(), "message", f.message()))
                .toList();

        return Map.of(
                "applied", applied,
                "failures", failures
        );
    }

    private HttpHandler get(Supplier<Response> logic) {
        return exchange -> {
            try (exchange) {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    send(exchange, 405, "application/json", write(Map.of("error", "GET only")));
                    return;
                }
                Response response = guarded(logic);
                send(exchange, response.status(), "application/json", write(response.body()));
            }
        };
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
                    JsonNode body = json.readTree(exchange.getRequestBody());
                    response = guarded(() -> logic.apply(body));
                } catch (JsonProcessingException e) {
                    response = new Response(400, Map.of("error", String.valueOf(e.getMessage())));
                }
                send(exchange, response.status(), "application/json", write(response.body()));
            }
        };
    }

    /** One place that turns exceptions into HTTP statuses, for both GET and POST. */
    private Response guarded(Supplier<Response> logic) {
        try {
            return logic.get();
        } catch (IllegalArgumentException e) {
            return new Response(400, Map.of("error", String.valueOf(e.getMessage())));
        } catch (NotConnectedException e) {
            return new Response(409, Map.of("error", e.getMessage()));
        } catch (PlanNotFoundException | AccountNotFoundException e) {
            return new Response(404, Map.of("error", e.getMessage()));
        } catch (ConnectionFailedException e) {
            AppLog.error(WebServer.class, e.getMessage(), e);
            return new Response(502, Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            AppLog.error(WebServer.class, e.getMessage(), e);
            return new Response(500, Map.of("error", "internal error"));
        }
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