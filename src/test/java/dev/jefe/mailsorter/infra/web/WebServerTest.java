package dev.jefe.mailsorter.infra.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jefe.mailsorter.AppConfig;
import dev.jefe.mailsorter.application.Fakes;
import dev.jefe.mailsorter.application.exception.ConnectionFailedException;
import dev.jefe.mailsorter.application.exception.NotConnectedException;
import dev.jefe.mailsorter.application.port.in.*;
import dev.jefe.mailsorter.application.port.in.dto.ApplyResult;
import dev.jefe.mailsorter.application.port.in.dto.ClassifyCommand;
import dev.jefe.mailsorter.application.port.in.dto.Plan;
import dev.jefe.mailsorter.application.port.out.*;
import dev.jefe.mailsorter.application.port.out.dto.MailOperation;
import dev.jefe.mailsorter.application.service.AccountService;
import dev.jefe.mailsorter.application.service.ActivityService;
import dev.jefe.mailsorter.application.service.AutopilotService;
import dev.jefe.mailsorter.application.port.out.dto.ActivityEntry;
import dev.jefe.mailsorter.infra.persistence.memory.InMemoryActivityRepository;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import dev.jefe.mailsorter.application.service.RuleService;
import dev.jefe.mailsorter.infra.persistence.memory.InMemoryAutopilotSettingsStore;
import dev.jefe.mailsorter.domain.config.MailboxConfig;

import dev.jefe.mailsorter.domain.model.*;
import dev.jefe.mailsorter.infra.persistence.memory.InMemoryRuleStore;
import io.github.cdimascio.dotenv.Dotenv;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class WebServerTest {

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    // recorded by the test doubles
    private final List<MailboxConfig> connected = new CopyOnWriteArrayList<>();
    private final List<ClassifyCommand> commands = new ArrayList<>();

    private volatile RuntimeException connectFailure;
    private volatile RuntimeException classifyFailure;
    private WebServer web;
    private SseBroadcaster events;
    private InMemoryActivityRepository activityRepo;

    @BeforeEach
    void start() throws IOException {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        AppConfig appConfig;
        try {
            appConfig = AppConfig.from(dotenv::get);
        } catch (IllegalStateException | IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }

        // ── test doubles for AccountService ────────────────────────────────
        MailProviderFactory factory = config -> {
            if (connectFailure != null) throw connectFailure;
            connected.add(config);

            String id = serviceKey(config) + "|" + config.username();   // same scheme as AccountService

            return new MailProvider() {
                @Override public String accountId()                         { return id; }

                @Override
                public void close() {

                }

                @Override public EmailContent read(String emailId)          { return null; }
                @Override public List<Email> listUnprocessed(int limit)     { return List.of(); }
                @Override public Set<String> applyAll(List<MailOperation> ops, String reviewLabel) {
                    return Set.of();
                }
                @Override public void markProcessed(String emailId)         {}
                @Override public void archive(String emailId)               {}
                @Override public void moveToTrash(String emailId)           {}
                @Override public void restoreToInbox(String emailId)        {}
                @Override public void addLabel(String emailId, String label){}
                @Override public void removeLabel(String emailId, String label) {}
            };
        };

        ConnectionStore connectionStore = new ConnectionStore() {
            private MailProvider current;
            @Override public void save(MailProvider provider) { this.current = provider; }
            @Override public Optional<MailProvider> current() { return Optional.ofNullable(current); }
        };

        CredentialStore credentialStore = new CredentialStore() {
            private final List<StoredCredential> store = new ArrayList<>();
            @Override public void save(String service, String username, String secret) {
                store.removeIf(c -> c.service().equals(service) && c.credential().username().equals(username));
                store.add(new StoredCredential(service, new Credential(username, secret)));
            }
            @Override public Optional<Credential> find(String service) { return Optional.empty(); }
            @Override public List<StoredCredential> findAll() { return List.copyOf(store); }
            @Override public void delete(String service, String username) {
                store.removeIf(c -> c.service().equals(service) && c.credential().username().equals(username));
            }
        };

        AccountService accountService = new AccountService(factory, connectionStore, credentialStore);

        // ── other use-cases stay as simple fakes ───────────────────────────
        ClassifyUseCase classify = command -> {
            if (classifyFailure != null) throw classifyFailure;
            commands.add(command);
            var entry = new Plan.Entry(
                    "<1@t>",
                    "offers@shop.com",
                    "Summer SALE",
                    new Classification(Category.PROMOTION, 0.95, "jev"),
                    Action.MOVE_TO_TRASH
            );
            return new Plan("test-plan-id", List.of(entry));
        };

        RuleRepository testRuleRepo = new InMemoryRuleStore();
        RuleService ruleService = new RuleService(testRuleRepo, ignored -> {});

        ApplyPlanUseCase apply = command -> new ApplyResult(Map.of(), List.of());
        GetEmailUseCase getEmail = Fakes::fakeEmail;

        Scheduler noopScheduler = new Scheduler() {
            @Override public void start(Runnable task, Duration interval) {}
            @Override public void stop() {}
        };
        events = new SseBroadcaster();
        AutopilotUseCase autopilot = new AutopilotService(classify, apply, accountService,
                new InMemoryAutopilotSettingsStore(), noopScheduler, events, Clock.systemUTC());

        activityRepo = new InMemoryActivityRepository();
        ActivityUseCase activity = new ActivityService(activityRepo);

        web = new WebServer("127.0.0.1", 0, accountService, classify, apply, getEmail, ruleService,
                autopilot, activity, events);
        web.start();
    }

    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port() + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void connectGmx() throws Exception {
        assertThat(post("/api/connect", """
            {"provider":"gmx","username":"me@gmx.com","secret":"x"}""").statusCode()).isEqualTo(200);
    }

    @AfterEach
    void stop() {
        web.close();
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String serviceKey(MailboxConfig config) {
        return switch (config.providerType().toLowerCase()) {
            case "gmx", "gmail", "outlook", "yahoo", "icloud" ->
                    config.providerType().toLowerCase();
            default -> "imap:" + config.host();
        };
    }

    // ── existing tests (unchanged assertions) ──────────────────────────────

    @Test
    void aFailedConnectionIs502AndNeverEchoesTheSecret() throws Exception {
        connectFailure = new ConnectionFailedException("authentication failed");

        var response = post("/api/connect", """
            {"host":"mail.example.com","port":993,"username":"me@example.com","secret":"s3cret"}""");

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).contains("authentication failed").doesNotContain("s3cret");
        assertThat(connected).isEmpty();          // factory never succeeded
    }

    @Test
    void anInvalidConfigIs400() throws Exception {
        var response = post("/api/connect", """
            {"host":" ","port":993,"username":"me@example.com","secret":"x"}""");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(connected).isEmpty();
    }

    @Test
    void classifyCreatesAPlanAndReturnsIt() throws Exception {
        var response = post("/api/classify", "{\"limit\":5}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(commands).containsExactly(new ClassifyCommand(5));

        var body = json.readTree(response.body());
        assertThat(body.get("planId").asText()).isEqualTo("test-plan-id");
        assertThat(body.at("/counts/MOVE_TO_TRASH").asInt()).isEqualTo(1);
        assertThat(body.at("/entries/0/subject").asText()).isEqualTo("Summer SALE");
        assertThat(body.at("/entries/0/category").asText()).isEqualTo("PROMOTION");
        assertThat(body.at("/entries/0/action").asText()).isEqualTo("MOVE_TO_TRASH");
    }

    @Test
    void classifyBeforeConnectingIs409() throws Exception {
        classifyFailure = new NotConnectedException();

        assertThat(post("/api/classify", "{}").statusCode()).isEqualTo(409);
    }

    // ── autopilot ───────────────────────────────────────────────────────────

    @Test
    void autopilotStatusIsOffByDefault() throws Exception {
        var response = get("/api/autopilot");

        assertThat(response.statusCode()).isEqualTo(200);
        var body = json.readTree(response.body());
        assertThat(body.get("enabled").asBoolean()).isFalse();
        assertThat(body.get("intervalSeconds").asInt()).isPositive();
        assertThat(body.get("lastRunAt").isNull()).isTrue();
    }

    @Test
    void enablingAutopilotReturnsTheNewStatus() throws Exception {
        connectGmx();

        var response = post("/api/autopilot", """
            {"enabled":true,"intervalSeconds":60,"limit":15}""");

        assertThat(response.statusCode()).isEqualTo(200);
        var body = json.readTree(response.body());
        assertThat(body.get("enabled").asBoolean()).isTrue();
        assertThat(body.get("intervalSeconds").asInt()).isEqualTo(60);
        assertThat(body.get("limit").asInt()).isEqualTo(15);
        assertThat(json.readTree(get("/api/autopilot").body()).get("enabled").asBoolean()).isTrue();
    }

    @Test
    void enablingAutopilotWithoutAnAccountIs409() throws Exception {
        var response = post("/api/autopilot", """
            {"enabled":true,"intervalSeconds":60,"limit":15}""");

        assertThat(response.statusCode()).isEqualTo(409);
    }

    @Test
    void anOutOfRangeAutopilotIntervalIs400() throws Exception {
        connectGmx();

        var response = post("/api/autopilot", """
            {"enabled":true,"intervalSeconds":1,"limit":15}""");

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void autopilotCanBeTriggeredManually() throws Exception {
        connectGmx();
        post("/api/autopilot", """
            {"enabled":true,"intervalSeconds":60,"limit":15}""");

        var response = post("/api/autopilot/run", "{}");

        assertThat(response.statusCode()).isEqualTo(200);
        var body = json.readTree(response.body());
        assertThat(body.get("lastRunAt").asText()).isNotBlank();
        assertThat(body.get("lastError").isNull()).isTrue();
    }

    // ── stats, activity, live events ───────────────────────────────────────

    private void recordActivity(String id, ClassificationSource source) {
        activityRepo.record(new ActivityEntry(Instant.parse("2026-11-26T20:00:00Z"), "gmx|me@gmx.com", id,
                "news@shop.com", "Weekly deals", Category.NEWSLETTER, 0.97, "list header",
                Action.ARCHIVE, source, false));
    }

    @Test
    void statsSummariseTheActivity() throws Exception {
        recordActivity("1", ClassificationSource.RULE);
        recordActivity("2", ClassificationSource.MODEL);

        var response = get("/api/stats");

        assertThat(response.statusCode()).isEqualTo(200);
        var body = json.readTree(response.body());
        assertThat(body.get("totalSorted").asLong()).isEqualTo(2);
        assertThat(body.get("ruleHits").asLong()).isEqualTo(1);
        assertThat(body.get("jevHits").asLong()).isEqualTo(1);
        assertThat(body.get("manualOverrides").asLong()).isZero();
    }

    @Test
    void activityListsRecentEntriesInTheShapeTheUiReads() throws Exception {
        recordActivity("1", ClassificationSource.RULE);

        var response = get("/api/activity?limit=5");

        assertThat(response.statusCode()).isEqualTo(200);
        var row = json.readTree(response.body()).get(0);
        assertThat(row.get("emailId").asText()).isEqualTo("1");
        assertThat(row.get("from").asText()).isEqualTo("news@shop.com");
        assertThat(row.get("subject").asText()).isEqualTo("Weekly deals");
        assertThat(row.get("category").asText()).isEqualTo("NEWSLETTER");
        assertThat(row.get("action").asText()).isEqualTo("ARCHIVE");
        assertThat(row.get("source").asText()).isEqualTo("RULE");
        assertThat(row.get("reason").asText()).isEqualTo("list header");
        assertThat(row.get("timestamp").asText()).isEqualTo("2026-11-26T20:00:00Z");
    }

    @Test
    void anInvalidActivityLimitIs400() throws Exception {
        assertThat(get("/api/activity?limit=abc").statusCode()).isEqualTo(400);
    }

    @Test
    void liveEventsAreStreamedToTheUi() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port() + "/api/events")).GET().build();
        HttpResponse<Stream<String>> response = http.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .get(2, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("text/event-stream");

        events.publish("autopilot", "Sorted 3 email(s)");

        var lines = response.body().iterator();
        String line;
        do { line = lines.next(); } while (!line.startsWith("event:"));
        assertThat(line).isEqualTo("event: autopilot");
        assertThat(lines.next()).isEqualTo("data: Sorted 3 email(s)");
        response.body().close();
    }

    @Test
    void servesTheUiAtTheRoot() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port() + "/")).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("<title>Mail Sorter</title>");
    }
}