package dev.jefe.mailsorter.application.service.prefilter;

import dev.jefe.mailsorter.application.port.out.Classifier;
import dev.jefe.mailsorter.application.service.prefilter.rules.InvoiceSignalRule;
import dev.jefe.mailsorter.application.service.prefilter.rules.ListUnsubscribeRule;
import dev.jefe.mailsorter.application.service.prefilter.rules.PrefilterRule;
import dev.jefe.mailsorter.application.service.prefilter.rules.RuleField;
import dev.jefe.mailsorter.domain.model.Action;
import dev.jefe.mailsorter.domain.model.Category;
import dev.jefe.mailsorter.domain.model.Classification;
import dev.jefe.mailsorter.domain.model.ClassificationSource;
import dev.jefe.mailsorter.domain.model.Email;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;


class PreFilteringClassifierTest {

    private final AtomicInteger delegateCalls = new AtomicInteger();
    private final Classifier delegate = email -> {
        delegateCalls.incrementAndGet();
        return new Classification(Category.UNKNOWN, 0.5, "delegate");
    };
    private final PreFilteringClassifier classifier = new PreFilteringClassifier(
            delegate, List.of(new InvoiceSignalRule(), new ListUnsubscribeRule()));

    private static Email email(String subject, Map<String, String> headers) {
        return new Email("1", "sender@shop.com", subject, "snippet", headers);
    }

    @Test
    void classifiesAsNewsletterWithoutCallingTheDelegateWhenListUnsubscribeIsPresent() {
        var result = classifier.classify(email("Weekly deals", Map.of("List-Unsubscribe", "<mailto:u@shop.com>")));

        assertThat(result.category()).isEqualTo(Category.NEWSLETTER);
        assertThat(delegateCalls).hasValue(0);
    }

    @Test
    void invoiceSignalWinsOverListUnsubscribe() {
        var result = classifier.classify(email("Your invoice #123", Map.of("List-Unsubscribe", "<mailto:u@shop.com>")));

        assertThat(result.category()).isEqualTo(Category.INVOICE);
        assertThat(delegateCalls).hasValue(0);
    }

    @Test
    void invoiceSignalIsCaseInsensitiveAndUnderstandsSpanish() {
        assertThat(classifier.classify(email("RECEIPT for your order", Map.of())).category())
                .isEqualTo(Category.INVOICE);
        assertThat(classifier.classify(email("Tu factura de noviembre", Map.of())).category())
                .isEqualTo(Category.INVOICE);
    }

    @Test
    void delegatesWhenNoRuleMatches() {
        var result = classifier.classify(email("Lunch tomorrow?", Map.of()));

        assertThat(result.reason()).isEqualTo("delegate");
        assertThat(delegateCalls).hasValue(1);
    }

    @Test
    void aRuleHitIsMarkedAsComingFromARule() {
        var result = classifier.classify(email("Your invoice #123", Map.of()));

        assertThat(result.source()).isEqualTo(ClassificationSource.RULE);
    }

    @Test
    void aUserFolderRuleAheadOfTheBuiltInsDecidesAndKeepsItsFolder() {
        var ikea = new PrefilterRule("r1", RuleField.FROM_DOMAIN, "shop.com", Action.MOVE_TO_FOLDER, Category.OTHER, "Ikea");
        var withUserRulesFirst = new PreFilteringClassifier(delegate, List.of(ikea, new InvoiceSignalRule()));

        var result = withUserRulesFirst.classify(email("Your invoice #123", Map.of()));

        assertThat(result.folder()).isEqualTo("Ikea");
        assertThat(result.source()).isEqualTo(ClassificationSource.RULE);
        assertThat(delegateCalls).hasValue(0);
    }

    @Test
    void aDelegatedResultIsMarkedAsComingFromTheModel() {
        var result = classifier.classify(email("Lunch tomorrow?", Map.of()));

        assertThat(result.source()).isEqualTo(ClassificationSource.MODEL);
    }
}