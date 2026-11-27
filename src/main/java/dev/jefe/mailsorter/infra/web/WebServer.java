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
import dev.jefe.mailsorter.application.exception.FolderInUseException;
import dev.jefe.mailsorter.application.exception.PlanNotFoundException;
import dev.jefe.mailsorter.application.port.in.*;
import dev.jefe.mailsorter.application.port.in.dto.*;
import dev.jefe.mailsorter.application.port.out.dto.ActivityEntry;
import dev.jefe.mailsorter.application.service.AccountService;
import dev.jefe.mailsorter.application.service.RuleService;
import dev.jefe.mailsorter.application.service.prefilter.rules.RuleField;
import dev.jefe.mailsorter.domain.model.Action;
import dev.jefe.mailsorter.domain.model.Category;
import dev.jefe.mailsorter.domain.model.EmailContent;
import dev.jefe.mailsorter.domain.config.MailboxConfig;
import dev.jefe.mailsorter.domain.config.WellKnownEmailProviders;
import dev.jefe.mailsorter.infra.logging.AppLog;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
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
    private final ListRulesUseCase listRules;
    private final AddRuleUseCase addRule;
    private final DeleteRuleUseCase deleteRule;
    private final FolderUseCase folders;
    private final AutopilotUseCase autopilot;
    private final ActivityUseCase activity;
    private final SseBroadcaster events;
    private final ObjectMapper json = new ObjectMapper();

    public WebServer(String host, int port,
                     AccountService accountService,
                     ClassifyUseCase classify,
                     ApplyPlanUseCase apply,
                     GetEmailUseCase getEmail,
                     RuleService ruleService,
                     FolderUseCase folders,
                     AutopilotUseCase autopilot,
                     ActivityUseCase activity,
                     SseBroadcaster events) throws IOException {
        this.autopilot = autopilot;
        this.activity = activity;
        this.events = events;
        this.connectService = accountService;
        this.listAccounts = accountService;
        this.classify = classify;
        this.apply = apply;
        this.getEmail = getEmail;
        this.listRules = ruleService;
        this.addRule = ruleService;
        this.deleteRule = ruleService;
        this.folders = folders;

        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/api/connect", post(this::handleConnect));
        server.createContext("/api/accounts", get(this::handleListAccounts));
        server.createContext("/api/accounts/connect", post(this::handleConnectSaved));
        server.createContext("/api/rules", this::handleRules); // GET list, DELETE /{id}
        server.createContext("/api/rules/add", post(this::handleAddRule));
        server.createContext("/api/folders", this::handleFolders); // GET list, POST add, DELETE /{id}
        server.createContext("/api/classify", post(this::handleClassify));
        server.createContext("/api/apply", post(this::handleApply));
        server.createContext("/api/email", post(this::handleEmail));
        server.createContext("/api/autopilot", this::handleAutopilot); // GET status, POST configure
        server.createContext("/api/autopilot/run", post(body -> new Response(200, toJson(autopilot.runNow()))));
        server.createContext("/api/stats", get(() -> new Response(200, activity.stats())));
        server.createContext("/api/activity", this::handleActivity);   // GET ?limit=N
        server.createContext("/api/events", this::handleEvents);       // SSE stream
        server.createContext("/", this::serveUi);
        // virtual thread per exchange: a long-lived SSE stream must not block the other endpoints
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    // GET /api/activity?limit=50
    private void handleActivity(HttpExchange exchange) throws IOException {
        try (exchange) {
            Response r;
            if (!"GET".equals(exchange.getRequestMethod())) {
                r = new Response(405, Map.of("error", "GET only"));
            } else {
                r = guarded(() -> {
                    int limit = queryInt(exchange, "limit", 50);
                    List<Map<String, Object>> rows = activity.recent(limit).stream().map(WebServer::toJson).toList();
                    return new Response(200, rows);
                });
            }
            send(exchange, r.status(), "application/json", write(r.body()));
        }
    }

    // GET /api/events  (text/event-stream, stays open)
    private void handleEvents(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "application/json", write(Map.of("error", "GET only")));
                return;
            }
            events.subscribe(exchange);
        }
    }

    private static int queryInt(HttpExchange exchange, String name, int fallback) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return fallback;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                try {
                    return Integer.parseInt(pair.substring(eq + 1));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(name + " must be an integer");
                }
            }
        }
        return fallback;
    }

    private static Map<String, Object> toJson(ActivityEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", e.timestamp().toString());
        m.put("accountId", e.accountId());
        m.put("emailId", e.emailId());
        m.put("from", e.from());
        m.put("subject", e.subject());
        m.put("category", e.category().name());
        m.put("confidence", e.confidence());
        m.put("reason", e.reason());
        m.put("action", e.action().name());
        m.put("source", e.source().name());
        m.put("overridden", e.overridden());
        m.put("folder", e.folder());
        return m;
    }

    // GET /api/autopilot  |  POST /api/autopilot {enabled, intervalSeconds, limit}
    private void handleAutopilot(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            Response r;
            if ("GET".equals(method)) {
                r = guarded(() -> new Response(200, toJson(autopilot.getStatus())));
            } else if ("POST".equals(method)) {
                try {
                    JsonNode body = json.readTree(exchange.getRequestBody());
                    r = guarded(() -> {
                        if (!body.path("enabled").isBoolean()) {
                            throw new IllegalArgumentException("enabled (boolean) is required");
                        }
                        var current = autopilot.getStatus();
                        var status = autopilot.setEnabled(
                                body.get("enabled").asBoolean(),
                                body.path("intervalSeconds").asInt(current.intervalSeconds()),
                                body.path("limit").asInt(current.limit()));
                        return new Response(200, toJson(status));
                    });
                } catch (JsonProcessingException e) {
                    r = new Response(400, Map.of("error", String.valueOf(e.getMessage())));
                }
            } else {
                r = new Response(405, Map.of("error", "GET or POST only"));
            }
            send(exchange, r.status(), "application/json", write(r.body()));
        }
    }

    private static Map<String, Object> toJson(AutopilotStatus status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", status.enabled());
        m.put("intervalSeconds", status.intervalSeconds());
        m.put("limit", status.limit());
        m.put("lastRunAt", status.lastRunAt() == null ? null : status.lastRunAt().toString());
        m.put("lastResult", status.lastResult());
        m.put("lastError", status.lastError());
        return m;
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

    private void handleRules(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath(); // e.g. "/api/rules" or "/api/rules/12"

            if ("GET".equals(method) && "/api/rules".equals(path)) {
                Response r = guarded(() -> new Response(200, listRules.execute()));
                send(exchange, r.status(), "application/json", write(r.body()));
                return;
            }

            if ("POST".equals(method) && "/api/rules".equals(path)) {
                Response r;
                try {
                    JsonNode body = json.readTree(exchange.getRequestBody());
                    r = guarded(() -> handleAddRule(body));
                } catch (JsonProcessingException e) {
                    r = new Response(400, Map.of("error", String.valueOf(e.getMessage())));
                }
                send(exchange, r.status(), "application/json", write(r.body()));
                return;
            }

            if ("DELETE".equals(method) && path.startsWith("/api/rules/")) {
                String rawId = path.substring("/api/rules/".length());
                Response r = guarded(() -> {
                    if (rawId.isBlank()) {
                        throw new IllegalArgumentException("rule id is required");
                    }
                    deleteRule.execute(rawId);
                    return new Response(200, Map.of("deleted", true, "id", rawId));
                });
                send(exchange, r.status(), "application/json", write(r.body()));
                return;
            }

            send(exchange, 405, "application/json", write(Map.of("error", "Method not allowed")));
        }
    }

    // GET /api/folders | POST /api/folders {name, description} | DELETE /api/folders/{id}
    private void handleFolders(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            Response r;

            if ("GET".equals(method) && "/api/folders".equals(path)) {
                r = guarded(() -> new Response(200, folders.list()));
            } else if ("POST".equals(method) && "/api/folders".equals(path)) {
                try {
                    JsonNode body = json.readTree(exchange.getRequestBody());
                    r = guarded(() -> new Response(201, folders.add(
                            body.path("name").asText(""), body.path("description").asText(""))));
                } catch (JsonProcessingException e) {
                    r = new Response(400, Map.of("error", String.valueOf(e.getOriginalMessage())));
                }
            } else if ("PUT".equals(method) && path.startsWith("/api/folders/")) {
                String id = path.substring("/api/folders/".length());
                try {
                    JsonNode body = json.readTree(exchange.getRequestBody());
                    r = guarded(() -> new Response(200, folders.updateDescription(id,
                            body.path("description").asText(""))));
                } catch (JsonProcessingException e) {
                    r = new Response(400, Map.of("error", String.valueOf(e.getOriginalMessage())));
                }
            } else if ("DELETE".equals(method) && path.startsWith("/api/folders/")) {
                String id = path.substring("/api/folders/".length());
                r = guarded(() -> {
                    folders.delete(id);
                    return new Response(200, Map.of("deleted", true, "id", id));
                });
            } else {
                r = new Response(405, Map.of("error", "Method not allowed"));
            }
            send(exchange, r.status(), "application/json", write(r.body()));
        }
    }

    private Response handleAddRule(JsonNode body) {
        String fieldRaw = body.path("field").asText("").trim();
        String pattern = body.path("pattern").asText("").trim();
        String actionRaw = body.path("action").asText("").trim();
        String categoryRaw = body.path("category").asText("").trim();
        String folder = body.path("folder").asText("").trim();

        if (fieldRaw.isEmpty()) throw new IllegalArgumentException("field is required");
        if (pattern.isEmpty()) throw new IllegalArgumentException("pattern is required");

        RuleField field;
        try {
            field = RuleField.valueOf(fieldRaw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid field: " + fieldRaw);
        }

        Action action = null; // optional for a folder rule: the service makes it MOVE_TO_FOLDER
        if (!actionRaw.isEmpty()) {
            try {
                action = Action.valueOf(actionRaw.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("invalid action: " + actionRaw);
            }
        }

        Category category = parseCategory(categoryRaw);

        var created = addRule.execute(new AddRuleCommand(field, pattern, action, category, folder));
        return new Response(201, created);
    }

    private static Category parseCategory(String raw) {
        if (raw.isEmpty() || "Rule".equalsIgnoreCase(raw)) {
            return Category.OTHER; // swap to whichever Category constant represents uncategorized/rule
        }
        try {
            return Category.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Category.OTHER;
        }
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
            m.put("folder", e.classification().folder());
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
        } catch (NotConnectedException | FolderInUseException e) {
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