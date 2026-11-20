package dev.jefe.mailsorter.infra.jev;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jefe.mailsorter.application.exception.ClassificationFailedException;
import dev.jefe.mailsorter.infra.logging.AppLog;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public final class JevClient {

    public record JevResponse(String model, JsonNode answers) {}

    private final JevConfig config;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    public JevClient(JevConfig config) { this.config = config; }

    public JevResponse evaluate(JsonNode state, JsonNode questions) {
        String body = body(state, questions);

        HttpRequest request = HttpRequest.newBuilder(config.endpoint())
                .timeout(config.timeout())
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                var ex = new ClassificationFailedException("JEV returned HTTP " + response.statusCode());
                AppLog.error(JevClient.class, ex.getMessage(), ex);
                throw ex;
            }
            return parse(response.body());
        } catch (IOException e) {
            var ex = new ClassificationFailedException("Jev call failed: " + e.getMessage(), e);
            AppLog.error(JevClient.class, ex.getMessage(), ex);
            throw ex;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            var ex = new ClassificationFailedException("Jev call interrupted", e);
            AppLog.error(JevClient.class, ex.getMessage(), ex);
            throw ex;
        }
    }

    private String body(JsonNode state, JsonNode questions) {
        ObjectNode root = json.createObjectNode();
        root.put("model", "jev-latest");
        root.set("state", state);
        root.set("questions", questions);
        return root.toString();
    }

    private JevResponse parse(String body) throws JsonProcessingException {
        JsonNode root = json.readTree(body);
        JsonNode answers = root.path("answers");
        if (!answers.isObject()) {
            var ex = new ClassificationFailedException("Jev response has no 'answers' object");
            AppLog.error(JevClient.class, ex.getMessage(), ex);
            throw ex;
        }
        return new JevResponse(root.path("model").asText("jev"), answers);
    }
}