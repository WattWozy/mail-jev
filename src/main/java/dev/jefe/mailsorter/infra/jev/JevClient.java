package dev.jefe.mailsorter.infra.jev;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jefe.mailsorter.application.exception.ClassificationFailedException;

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

        System.out.println("=== JEV REQUEST ===");
        System.out.println("URL     : " + config.endpoint());
        System.out.println("API Key : " + config.apiKey().substring(0, Math.min(8, config.apiKey().length())) + "...");
        System.out.println("Auth    : Bearer " + config.apiKey().substring(0, Math.min(8, config.apiKey().length())) + "...");
        System.out.println("Body    : " + body);
        System.out.println("===================");

        HttpRequest request = HttpRequest.newBuilder(config.endpoint())
                .timeout(config.timeout())
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new ClassificationFailedException("Jev returned HTTP " + response.statusCode());
            }
            return parse(response.body());
        } catch (IOException e) {
            throw new ClassificationFailedException("Jev call failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClassificationFailedException("Jev call interrupted", e);
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
            throw new ClassificationFailedException("Jev response has no 'answers' object");
        }
        return new JevResponse(root.path("model").asText("jev"), answers);
    }
}