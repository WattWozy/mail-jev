package dev.jefe.mailsorter.infra.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jefe.mailsorter.AppConfig;
import dev.jefe.mailsorter.application.exception.ConnectionFailedException;
import dev.jefe.mailsorter.application.exception.NotConnectedException;
import dev.jefe.mailsorter.application.port.in.*;
import dev.jefe.mailsorter.domain.Action;
import dev.jefe.mailsorter.domain.Category;
import dev.jefe.mailsorter.domain.Classification;
import dev.jefe.mailsorter.domain.EmailContent;
import dev.jefe.mailsorter.domain.config.MailboxConfig;
import io.github.cdimascio.dotenv.Dotenv;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;


class WebServerTest {

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<MailboxConfig> connected = new ArrayList<>();
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


        ConnectUseCase connect = config -> {
            if (connectFailure != null) throw connectFailure;
            connected.add(config);
        };

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

        // simple no-op apply for the web tests (or capture calls if you want)
        ApplyPlanUseCase apply = command -> {
            // you can collect the command here if the tests assert on it
            return new ApplyResult(Map.of(), List.of());
        };

        GetEmailUseCase getEmail = WebServerTest::fakeEmail;

        web = new WebServer("127.0.0.1", 0, connect, classify, apply, getEmail);
        web.start();
    }

    @AfterEach
    void stop() { web.close(); }

    private HttpResponse<String> post(String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aFailedConnectionIs502AndNeverEchoesTheSecret() throws Exception {
        connectFailure = new ConnectionFailedException("authentication failed");

        var response = post("/api/connect", """
            {"host":"mail.example.com","port":993,"username":"me@example.com","secret":"s3cret"}""");

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).contains("authentication failed").doesNotContain("s3cret");
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

    static EmailContent fakeEmail(String emailId) {
        return new EmailContent(emailId,
                "From test",
                "Test subject",
                "Test date",
                " : some test body  :");
    }
}
