package dev.jefe.mailsorter.infra.jev;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jefe.mailsorter.application.exception.ClassificationFailedException;
import dev.jefe.mailsorter.domain.Category;
import dev.jefe.mailsorter.domain.Email;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

class JevClassifierTest {

    private final ObjectMapper json = new ObjectMapper();
    private StubJevServer jev;
    private JevClassifier classifier;

    private final Email email = new Email("1", "news@shop.com", "Weekly deals", "Big discounts inside", Map.of());

    @BeforeEach
    void setUp() throws IOException {
        jev = new StubJevServer();
        classifier = new JevClassifier(
                new JevClient(new JevConfig(jev.uri(), "test-key", Duration.ofSeconds(2))), 100);
    }

    @AfterEach
    void tearDown() { jev.close(); }

    private static String choiceResponse(String choice, double confidence, double probability) {
        return """
            {"model":"jev-1.13.0","answers":{"category":{"type":"choice","choice":"%s",
             "confidence":%s,"probabilities":{"%s":%s}}}}
            """.formatted(choice, confidence, choice, probability);
    }

    @Test
    void mapsTheChoiceAnswerToAClassification() {
        jev.responseBody = choiceResponse("newsletter", 0.9, 0.95);

        var result = classifier.classify(email);

        assertThat(result.category()).isEqualTo(Category.NEWSLETTER);
        assertThat(result.confidence()).isEqualTo(0.9); // lower of confidence and probability
    }

    @Test
    void takesTheLowerOfConfidenceAndProbability() {
        jev.responseBody = choiceResponse("promotion", 0.99, 0.6);

        assertThat(classifier.classify(email).confidence()).isEqualTo(0.6);
    }

    @Test
    void sendsTheEmailAsStateAndOneChoiceQuestionOverAllCategories() throws Exception {
        jev.responseBody = choiceResponse("newsletter", 0.9, 0.9);

        classifier.classify(email);

        var body = json.readTree(jev.lastBody);
        assertThat(body.at("/state/subject").asText()).isEqualTo("Weekly deals");
        assertThat(body.at("/state/from").asText()).isEqualTo("news@shop.com");
        assertThat(body.at("/questions/category/type").asText()).isEqualTo("choice");
        assertThat(body.at("/questions/category/criteria").fieldNames())
                .toIterable().contains("newsletter", "promotion", "invoice", "personal", "unknown");
        assertThat(jev.lastAuth).isEqualTo("Bearer test-key");
    }

    @Test
    void truncatesLongSnippets() throws Exception {
        jev.responseBody = choiceResponse("newsletter", 0.9, 0.9);
        var long_ = new Email("1", "a@b.com", "s", "x".repeat(5000), Map.of());

        classifier.classify(long_);

        assertThat(json.readTree(jev.lastBody).at("/state/snippet").asText()).hasSize(100);
    }

    @Test
    void anUnrecognisedCategoryBecomesUnknownWithZeroConfidence() {
        jev.responseBody = choiceResponse("weird", 0.9, 0.9);

        var result = classifier.classify(email);

        assertThat(result.category()).isEqualTo(Category.UNKNOWN);
        assertThat(result.confidence()).isZero();
    }

    @Test
    void failsOnServerError() {
        jev.status = 500;

        assertThatThrownBy(() -> classifier.classify(email)).isInstanceOf(ClassificationFailedException.class);
    }

    @Test
    void failsOnAResponseWithoutAnAnswer() {
        jev.responseBody = "{\"model\":\"jev-1.13.0\",\"answers\":{}}";

        assertThatThrownBy(() -> classifier.classify(email)).isInstanceOf(ClassificationFailedException.class);
    }

    @Test
    void failsWhenJevIsUnreachable() {
        jev.close();

        assertThatThrownBy(() -> classifier.classify(email)).isInstanceOf(ClassificationFailedException.class);
    }
}