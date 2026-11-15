package dev.jefe.mailsorter.infra.jev;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jefe.mailsorter.application.exception.ClassificationFailedException;
import dev.jefe.mailsorter.application.port.out.Classifier;
import dev.jefe.mailsorter.domain.Category;
import dev.jefe.mailsorter.domain.Classification;
import dev.jefe.mailsorter.domain.Email;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class JevClassifier implements Classifier {

    private static final String QUESTION = "category";

    private static final Map<Category, String> CRITERIA = new LinkedHashMap<>();
    static {
        CRITERIA.put(Category.NEWSLETTER, "Recurring newsletters and mailing lists the user subscribed to");
        CRITERIA.put(Category.PROMOTION, "Marketing offers, discounts and advertising");
        CRITERIA.put(Category.SOCIAL, "Notifications from social networks");
        CRITERIA.put(Category.INVOICE, "Invoices, receipts, payment and order confirmations");
        CRITERIA.put(Category.PERSONAL, "Messages written by a real person directly to the user");
        CRITERIA.put(Category.SPAM, "Unsolicited junk mail or phishing");
        CRITERIA.put(Category.UNKNOWN, "Does not clearly fit any other category");
    }

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final JevClient client;
    private final int maxSnippetChars;

    public JevClassifier(JevClient client, int maxSnippetChars) {
        if (maxSnippetChars <= 0) {
            throw new IllegalArgumentException("maxSnippetChars must be positive");
        }
        this.client = client;
        this.maxSnippetChars = maxSnippetChars;
    }

    @Override
    public Classification classify(Email email) {
        JevClient.JevResponse response = client.evaluate(state(email), questions());
        return toClassification(response);
    }

    private ObjectNode state(Email email) {
        ObjectNode state = JSON.objectNode();
        state.put("from", email.from());
        state.put("subject", email.subject());
        state.put("snippet", truncate(email.snippet()));
        return state;
    }

    private static ObjectNode questions() {
        ObjectNode questions = JSON.objectNode();
        ObjectNode question = questions.putObject(QUESTION);
        question.put("type", "choice");
        question.put("instructions", "Which category best describes this email?");
        ObjectNode criteria = question.putObject("criteria");
        CRITERIA.forEach((category, description) -> criteria.put(name(category), description));
        return questions;
    }

    private static Classification toClassification(JevClient.JevResponse response) {
        JsonNode answer = response.answers().path(QUESTION);
        if (!answer.hasNonNull("choice")) {
            throw new ClassificationFailedException("Jev response has no answer for '" + QUESTION + "'");
        }
        String choice = answer.get("choice").asText();
        double confidence = answer.path("confidence").asDouble(0.0);
        double probability = answer.path("probabilities").path(choice).asDouble(confidence);
        String reason = response.model() + " p=" + probability;

        return CRITERIA.keySet().stream()
                .filter(c -> name(c).equalsIgnoreCase(choice))
                .findFirst()
                .map(c -> new Classification(c, Math.min(confidence, probability), reason))
                .orElseGet(() -> new Classification(Category.UNKNOWN, 0.0, reason + " (unrecognised: " + choice + ")"));
    }

    private String truncate(String text) {
        if (text == null) return "";
        return text.length() <= maxSnippetChars ? text : text.substring(0, maxSnippetChars);
    }

    private static String name(Category c) { return c.name().toLowerCase(Locale.ROOT); }
}