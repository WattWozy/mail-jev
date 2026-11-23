package dev.jefe.mailsorter.infra.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jefe.mailsorter.AppConfig;
import dev.jefe.mailsorter.application.Fakes;
import dev.jefe.mailsorter.application.exception.ConnectionFailedException;
import dev.jefe.mailsorter.application.exception.NotConnectedException;
import dev.jefe.mailsorter.application.port.in.*;
import dev.jefe.mailsorter.application.port.out.*;
import dev.jefe.mailsorter.application.service.AccountService;
import dev.jefe.mailsorter.application.service.RuleService;
import dev.jefe.mailsorter.application.service.prefilter.Rule;
import dev.jefe.mailsorter.domain.*;
import dev.jefe.mailsorter.domain.config.MailboxConfig;

import dev.jefe.mailsorter.infra.memory.inMemory.InMemoryRuleStore;
import io.github.cdimascio.dotenv.Dotenv;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

        web = new WebServer("127.0.0.1", 0, accountService, classify, apply, getEmail, ruleService);
        web.start();
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

    @Test
    void servesTheUiAtTheRoot() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port() + "/")).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("<title>Mail Sorter</title>");
    }
}