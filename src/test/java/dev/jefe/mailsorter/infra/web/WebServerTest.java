package dev.jefe.mailsorter.infra.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jefe.mailsorter.application.exception.ConnectionFailedException;
import dev.jefe.mailsorter.application.exception.NotConnectedException;
import dev.jefe.mailsorter.application.port.in.ClassificationReport;
import dev.jefe.mailsorter.application.port.in.ClassifyCommand;
import dev.jefe.mailsorter.application.port.in.ClassifyUseCase;
import dev.jefe.mailsorter.application.port.in.ConnectUseCase;
import dev.jefe.mailsorter.domain.Action;
import dev.jefe.mailsorter.domain.Category;
import dev.jefe.mailsorter.domain.Classification;
import dev.jefe.mailsorter.domain.MailboxConfig;
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
        ConnectUseCase connect = config -> {
            if (connectFailure != null) throw connectFailure;
            connected.add(config);
        };
        ClassifyUseCase classify = command -> {
            if (classifyFailure != null) throw classifyFailure;
            commands.add(command);
            var entry = new ClassificationReport.Entry("<1@t>", "offers@shop.com", "Summer SALE",
                    new Classification(Category.PROMOTION, 0.95, "jev"), Action.MOVE_TO_TRASH);
            return new ClassificationReport(List.of(entry), command.dryRun());
        };
        web = new WebServer("127.0.0.1", 0, connect, classify);
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

//    @Test
//    void connectPassesTheParsedConfigToTheUseCase() throws Exception {
//        var response = post("/api/connect", """
//            {"providerType":"imap","host":"mail.example.com","port":993,"ssl"="true",
//             "username":"me@example.com","secret":"s3cret","options":{"ssl":"false"}}""");
//
//        assertThat(response.statusCode()).isEqualTo(200);
//        assertThat(connected).hasSize(1);
//        assertThat(connected.get(0).host()).isEqualTo("mail.example.com");
//        assertThat(connected.get(0).options()).containsEntry("ssl", "true");
//    }

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
    void classifyDefaultsToPreviewAndReturnsTheReport() throws Exception {
        var response = post("/api/classify", "{\"limit\":5}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(commands).containsExactly(ClassifyCommand.preview(5));
        var body = json.readTree(response.body());
        assertThat(body.get("dryRun").asBoolean()).isTrue();
        assertThat(body.at("/counts/MOVE_TO_TRASH").asInt()).isEqualTo(1);
        assertThat(body.at("/entries/0/subject").asText()).isEqualTo("Summer SALE");
        assertThat(body.at("/entries/0/category").asText()).isEqualTo("PROMOTION");
        assertThat(body.at("/entries/0/action").asText()).isEqualTo("MOVE_TO_TRASH");
    }

    @Test
    void classifyOnlyTouchesTheMailboxWhenApplyIsExplicitlyTrue() throws Exception {
        post("/api/classify", "{\"limit\":5,\"apply\":true}");

        assertThat(commands).containsExactly(ClassifyCommand.apply(5));
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
