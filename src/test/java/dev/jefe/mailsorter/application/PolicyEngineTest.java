package dev.jefe.mailsorter.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jefe.mailsorter.domain.Action;
import dev.jefe.mailsorter.domain.Category;
import dev.jefe.mailsorter.domain.Classification;
import java.util.Map;

import dev.jefe.mailsorter.domain.PolicyEngine;
import org.junit.jupiter.api.Test;

class PolicyEngineTest {

    private final PolicyEngine engine = new PolicyEngine(
            Map.of(
                    Category.NEWSLETTER, Action.ARCHIVE,
                    Category.PROMOTION, Action.MOVE_TO_TRASH,
                    Category.INVOICE, Action.KEEP),
            0.9);

    @Test
    void appliesConfiguredActionWhenConfidenceReachesThreshold() {
        var result = engine.decide(new Classification(Category.NEWSLETTER, 0.95, "has List-Unsubscribe"));

        assertThat(result).isEqualTo(Action.ARCHIVE);
    }

    @Test
    void thresholdIsInclusive() {
        var result = engine.decide(new Classification(Category.NEWSLETTER, 0.9, "edge"));

        assertThat(result).isEqualTo(Action.ARCHIVE);
    }

    @Test
    void flagsForReviewWhenConfidenceIsBelowThreshold() {
        var result = engine.decide(new Classification(Category.PROMOTION, 0.89, "unsure"));

        assertThat(result).isEqualTo(Action.FLAG_FOR_REVIEW);
    }

    @Test
    void keepsEmailWhenCategoryHasNoConfiguredAction() {
        var result = engine.decide(new Classification(Category.PERSONAL, 0.99, "from a contact"));

        assertThat(result).isEqualTo(Action.KEEP);
    }

    @Test
    void neverTrashesInvoicesEvenIfMisconfigured() {
        var risky = new PolicyEngine(Map.of(Category.INVOICE, Action.MOVE_TO_TRASH), 0.5);

        var result = risky.decide(new Classification(Category.INVOICE, 0.99, "looks like spam"));

        assertThat(result).isEqualTo(Action.KEEP);
    }

    @Test
    void rejectsInvalidThreshold() {
        assertThatThrownBy(() -> new PolicyEngine(Map.of(), 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
