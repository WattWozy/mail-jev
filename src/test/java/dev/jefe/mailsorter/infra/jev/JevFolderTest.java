package dev.jefe.mailsorter.infra.jev;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jefe.mailsorter.application.port.in.dto.ClassifyCommand;
import dev.jefe.mailsorter.application.service.ClassifyService;
import dev.jefe.mailsorter.application.service.FolderService;
import dev.jefe.mailsorter.domain.model.Action;
import dev.jefe.mailsorter.domain.model.Category;
import dev.jefe.mailsorter.domain.model.Email;
import dev.jefe.mailsorter.domain.model.UserFolder;
import dev.jefe.mailsorter.domain.policy.PolicyEngine;
import dev.jefe.mailsorter.infra.persistence.memory.InMemoryConnectionStore;
import dev.jefe.mailsorter.infra.persistence.memory.InMemoryFolderStore;
import dev.jefe.mailsorter.infra.persistence.memory.InMemoryPlanStore;
import dev.jefe.mailsorter.infra.persistence.memory.InMemoryRuleStore;
import dev.jefe.mailsorter.testsupport.FakeMailProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** jev chooses between the built-in categories and the account's own folders, sorting by their descriptions. */
class JevFolderTest {

    private static final String GMX = "gmx|me@gmx.com";
    private static final String GMAIL = "gmail|me@gmail.com";

    private final ObjectMapper json = new ObjectMapper();
    private final InMemoryFolderStore folders = new InMemoryFolderStore();
    private final AtomicInteger folderLookups = new AtomicInteger();
    private final Email email = new Email("1", "deals@airline.com", "Fares from 52 EUR", "Book now", Map.of());
    private StubJevServer jev;
    private JevClassifier classifier;

    @BeforeEach
    void setUp() throws IOException {
        jev = new StubJevServer();
        classifier = new JevClassifier(
                new JevClient(new JevConfig(jev.uri(), "test-key", Duration.ofSeconds(2))), 100,
                account -> { folderLookups.incrementAndGet(); return folders.findAll(account); });
        folders.save(GMX, new UserFolder("f1", "Ikea", "Anything from or about IKEA"));
        folders.save(GMX, new UserFolder("f2", "Travel promotions", "Flight, hotel and holiday deals"));
    }

    @AfterEach
    void tearDown() { jev.close(); }

    private static String answer(String choice, double confidence) {
        return """
            {"model":"jev-1.14.0","answers":{"category":{"type":"choice","choice":"%s",
             "confidence":%s,"probabilities":{"%s":%s}}}}
            """.formatted(choice, confidence, choice, confidence);
    }

    private JsonNode criteriaSent() throws IOException {
        return json.readTree(jev.lastBody).at("/questions/category/criteria");
    }

    private List<String> choicesSent() throws IOException {
        List<String> names = new ArrayList<>();
        criteriaSent().fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void theAccountsFoldersAreOfferedNextToTheBuiltInCategories() throws IOException {
        jev.responseBody = answer("promotion", 0.9);

        classifier.classify(email, GMX);

        assertThat(choicesSent()).contains("newsletter", "promotion", "unknown", "folder_1", "folder_2");
        assertThat(criteriaSent().get("folder_2").asText())
                .contains("Travel promotions").contains("Flight, hotel and holiday deals");
    }

    @Test
    void aFolderAnswerFilesTheEmailIntoThatFolder() {
        jev.responseBody = answer("folder_2", 0.93);

        var result = classifier.classify(email, GMX);

        assertThat(result.folder()).isEqualTo("Travel promotions");
        assertThat(result.category()).isEqualTo(Category.OTHER);
        assertThat(result.confidence()).isEqualTo(0.93);
    }

    @Test
    void aBuiltInAnswerStillMapsToItsCategoryWithoutAFolder() {
        jev.responseBody = answer("promotion", 0.9);

        var result = classifier.classify(email, GMX);

        assertThat(result.category()).isEqualTo(Category.PROMOTION);
        assertThat(result.folder()).isNull();
    }

    @Test
    void anUnknownFolderAnswerIsUnknown() {
        jev.responseBody = answer("folder_9", 0.9);

        var result = classifier.classify(email, GMX);

        assertThat(result.category()).isEqualTo(Category.UNKNOWN);
        assertThat(result.folder()).isNull();
    }

    @Test
    void anotherAccountsFoldersAreNotOffered() throws IOException {
        jev.responseBody = answer("promotion", 0.9);

        classifier.classify(email, GMAIL);

        assertThat(choicesSent()).noneMatch(c -> c.startsWith("folder_"));
    }

    @Test
    void withoutAnAccountNoFoldersAreOffered() throws IOException {
        jev.responseBody = answer("promotion", 0.9);

        classifier.classify(email);

        assertThat(choicesSent()).noneMatch(c -> c.startsWith("folder_"));
    }

    @Test
    void foldersAreLookedUpOncePerAccountUntilTheyChange() throws IOException {
        jev.responseBody = answer("promotion", 0.9);
        classifier.classify(email, GMX);
        classifier.classify(email, GMX);
        assertThat(folderLookups).hasValue(1);

        folders.save(GMX, new UserFolder("f3", "Job leads", "Job offers and recruiter mail"));
        classifier.foldersChanged();
        classifier.classify(email, GMX);

        assertThat(folderLookups).hasValue(2);
        assertThat(choicesSent()).contains("folder_3");
    }

    // -- end to end: a new folder is used on the next plan ----------------------------

    @Test
    void aNewFolderIsUsedOnTheNextPlanAndTheEmailIsMovedThere() {
        var connections = new InMemoryConnectionStore();
        var mailbox = new FakeMailProvider(GMAIL);
        mailbox.withEmails(email);
        connections.save(mailbox);
        var classify = new ClassifyService(connections, classifier, new PolicyEngine(Map.of(), 0.9),
                new InMemoryPlanStore());
        var folderService = new FolderService(folders, new InMemoryRuleStore(), connections, () -> {
            classifier.foldersChanged();
            classify.forgetCachedClassifications();
        });
        jev.responder = body -> body.at("/questions/category/criteria").has("folder_1")
                ? answer("folder_1", 0.95)
                : answer("promotion", 0.95);

        assertThat(classify.execute(new ClassifyCommand(10)).entries().get(0).classification().folder()).isNull();

        folderService.add("Travel promotions", "Flight, hotel and holiday deals");
        var entry = classify.execute(new ClassifyCommand(10)).entries().get(0);

        assertThat(entry.classification().folder()).isEqualTo("Travel promotions");
        assertThat(entry.action()).isEqualTo(Action.MOVE_TO_FOLDER);
    }
}
