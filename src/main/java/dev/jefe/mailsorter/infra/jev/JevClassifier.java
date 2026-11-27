package dev.jefe.mailsorter.infra.jev;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jefe.mailsorter.application.exception.ClassificationFailedException;
import dev.jefe.mailsorter.application.port.out.Classifier;
import dev.jefe.mailsorter.domain.model.Category;
import dev.jefe.mailsorter.domain.model.Classification;
import dev.jefe.mailsorter.domain.model.Email;
import dev.jefe.mailsorter.domain.model.UserFolder;
import dev.jefe.mailsorter.infra.logging.AppLog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * One jev "choice" question per email. The choices are the built-in categories plus, for a known account,
 * that account's own folders ("folder_1", "folder_2", ...), each described by the user's description.
 */
public final class JevClassifier implements Classifier {

    private static final String QUESTION = "category";
    private static final String FOLDER_PREFIX = "folder_";

    private static final Map<Category, String> CRITERIA = new LinkedHashMap<>();
    static {
        CRITERIA.put(Category.NEWSLETTER, "Recurring newsletters, digests, job alerts, and mailing lists the user subscribed to");
        CRITERIA.put(Category.PROMOTION, "Marketing offers, discounts, surveys, and promotional outreach");
        CRITERIA.put(Category.SOCIAL, "Notifications from social networks");
        CRITERIA.put(Category.INVOICE, "Invoices, receipts, payment and order confirmations");
        CRITERIA.put(Category.PERSONAL, "Messages written by a real person directly to the user");
        CRITERIA.put(Category.SPAM, "Unsolicited junk mail or phishing");
        CRITERIA.put(Category.SHIPMENT, "Shipping updates, delivery tracking, security alerts, and automated account notifications");
        CRITERIA.put(Category.UNKNOWN, "Only when none of the other categories apply");
    }

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final JevClient client;
    private final int maxSnippetChars;
    private final Function<String, List<UserFolder>> foldersFor;
    private final Map<String, List<UserFolder>> foldersByAccount = new ConcurrentHashMap<>();

    public JevClassifier(JevClient client, int maxSnippetChars) {
        this(client, maxSnippetChars, account -> List.of());
    }

    public JevClassifier(JevClient client, int maxSnippetChars, Function<String, List<UserFolder>> foldersFor) {
        if (maxSnippetChars <= 0) {
            throw new IllegalArgumentException("maxSnippetChars must be positive");
        }
        this.client = client;
        this.maxSnippetChars = maxSnippetChars;
        this.foldersFor = foldersFor;
    }

    /** Call when any account's folders change; they are looked up again on next use. */
    public void foldersChanged() {
        foldersByAccount.clear();
    }

    /** Without an account only the built-in categories are offered. */
    @Override
    public Classification classify(Email email) {
        return classify(email, List.of());
    }

    @Override
    public Classification classify(Email email, String accountId) {
        if (accountId == null) {
            return classify(email);
        }
        return classify(email, foldersByAccount.computeIfAbsent(accountId, a -> List.copyOf(foldersFor.apply(a))));
    }

    private Classification classify(Email email, List<UserFolder> folders) {
        JevClient.JevResponse response = client.evaluate(state(email), questions(folders));
        return toClassification(response, folders);
    }

    private ObjectNode state(Email email) {
        ObjectNode state = JSON.objectNode();
        String from = email.from() == null ? "" : email.from();
        state.put("from", from);
        state.put("subject", email.subject());
        state.put("snippet", truncate(email.snippet()));
        state.put("has_list_unsubscribe", email.header("List-Unsubscribe").isPresent());
        String lowerFrom = from.toLowerCase(Locale.ROOT);
        state.put("is_noreply", lowerFrom.contains("no-reply") || lowerFrom.contains("noreply"));
        return state;
    }

    private static ObjectNode questions(List<UserFolder> folders) {
        ObjectNode questions = JSON.objectNode();
        ObjectNode question = questions.putObject(QUESTION);
        question.put("type", "choice");
        question.put("instructions", folders.isEmpty()
                ? "Which category best describes this email?"
                : "Which category or folder best describes this email? Prefer a folder when its description fits.");
        ObjectNode criteria = question.putObject("criteria");
        CRITERIA.forEach((category, description) -> criteria.put(name(category), description));
        for (int i = 0; i < folders.size(); i++) {
            UserFolder folder = folders.get(i);
            criteria.put(FOLDER_PREFIX + (i + 1),
                    "The user's folder \"" + folder.name() + "\": " + folder.description());
        }
        return questions;
    }

    private static Classification toClassification(JevClient.JevResponse response, List<UserFolder> folders) {
        JsonNode answer = response.answers().path(QUESTION);
        if (!answer.hasNonNull("choice")) {
            AppLog.warn(JevClassifier.class, "JEV response has no answer for '" + QUESTION + "'");
            throw new ClassificationFailedException("Jev response has no answer for '" + QUESTION + "'");
        }
        String choice = answer.get("choice").asText();
        double confidence = answer.path("confidence").asDouble(0.0);
        double probability = answer.path("probabilities").path(choice).asDouble(confidence);
        double score = Math.min(confidence, probability);
        String reason = response.model() + " p=" + probability;

        UserFolder folder = folderFor(choice, folders);
        if (folder != null) {
            return new Classification(Category.OTHER, score, reason + " → " + folder.name()).withFolder(folder.name());
        }
        return CRITERIA.keySet().stream()
                .filter(c -> name(c).equalsIgnoreCase(choice))
                .findFirst()
                .map(c -> new Classification(c, score, reason))
                .orElseGet(() -> new Classification(Category.UNKNOWN, 0.0, reason + " (unrecognised: " + choice + ")"));
    }

    /** "folder_2" -> the second folder offered in this request; null for anything else. */
    private static UserFolder folderFor(String choice, List<UserFolder> folders) {
        if (!choice.startsWith(FOLDER_PREFIX)) return null;
        try {
            int index = Integer.parseInt(choice.substring(FOLDER_PREFIX.length())) - 1;
            return index >= 0 && index < folders.size() ? folders.get(index) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String truncate(String text) {
        if (text == null) return "";
        return text.length() <= maxSnippetChars ? text : text.substring(0, maxSnippetChars);
    }

    private static String name(Category c) { return c.name().toLowerCase(Locale.ROOT); }
}
