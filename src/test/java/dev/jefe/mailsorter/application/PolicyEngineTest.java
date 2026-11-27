package dev.jefe.mailsorter.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jefe.mailsorter.domain.model.Action;
import dev.jefe.mailsorter.domain.model.Category;
import dev.jefe.mailsorter.domain.model.Classification;
import java.util.Map;

import dev.jefe.mailsorter.domain.policy.PolicyEngine;
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

    // -- user folders -------------------------------------------------------------

    @Test
    void movesToTheUserFolderNamedByTheClassification() {
        var result = engine.decide(new Classification(Category.OTHER, 1.0, "rule").withFolder("Ikea"));

        assertThat(result).isEqualTo(Action.MOVE_TO_FOLDER);
    }

    @Test
    void aUserFolderWinsOverTheCategoryAction() {
        var result = engine.decide(new Classification(Category.PROMOTION, 0.95, "rule").withFolder("Travel promotions"));

        assertThat(result).isEqualTo(Action.MOVE_TO_FOLDER); // not MOVE_TO_TRASH
    }

    @Test
    void aProtectedCategoryCanStillBeFiledIntoAUserFolder() {
        var result = engine.decide(new Classification(Category.INVOICE, 0.99, "rule").withFolder("Invoices"));

        assertThat(result).isEqualTo(Action.MOVE_TO_FOLDER);
    }

    @Test
    void anUnsureUserFolderMatchIsFlaggedForReview() {
        var result = engine.decide(new Classification(Category.OTHER, 0.5, "model").withFolder("Ikea"));

        assertThat(result).isEqualTo(Action.FLAG_FOR_REVIEW);
    }

    @Test
    void aFolderMoveNeedsLessCertaintyThanArchivingBecauseItIsHarmless() {
        var result = engine.decide(new Classification(Category.OTHER, 0.8, "jev").withFolder("Ads"));

        assertThat(result).isEqualTo(Action.MOVE_TO_FOLDER); // the 0.9 threshold would flag it
    }

    @Test
    void aFolderMoveBelowSeventyPercentIsStillFlagged() {
        assertThat(engine.decide(new Classification(Category.OTHER, 0.69, "jev").withFolder("Ads")))
                .isEqualTo(Action.FLAG_FOR_REVIEW);
        assertThat(engine.decide(new Classification(Category.OTHER, 0.70, "jev").withFolder("Ads")))
                .isEqualTo(Action.MOVE_TO_FOLDER);
    }

    @Test
    void archivingStillNeedsTheDefaultThreshold() {
        assertThat(engine.decide(new Classification(Category.NEWSLETTER, 0.8, "jev")))
                .isEqualTo(Action.FLAG_FOR_REVIEW);
    }

    @Test
    void withoutAFolderNothingIsMovedToAFolder() {
        var result = engine.decide(new Classification(Category.OTHER, 1.0, "rule"));

        assertThat(result).isNotEqualTo(Action.MOVE_TO_FOLDER);
    }

    @Test
    void rejectsInvalidThreshold() {
        assertThatThrownBy(() -> new PolicyEngine(Map.of(), 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -- rule actions ---------------------------------------------------------------

    @Test
    void aRulesActionIsApplied() {
        var result = engine.decide(new Classification(Category.OTHER, 1.0, "rule").withAction(Action.MOVE_TO_TRASH));

        assertThat(result).isEqualTo(Action.MOVE_TO_TRASH);
    }

    @Test
    void aRulesActionBeatsTheCategoryAction() {
        var result = engine.decide(new Classification(Category.NEWSLETTER, 1.0, "rule").withAction(Action.KEEP));

        assertThat(result).isEqualTo(Action.KEEP); // NEWSLETTER alone would be ARCHIVE
    }

    @Test
    void aRuleCanNeverTrashAProtectedCategory() {
        var result = engine.decide(new Classification(Category.INVOICE, 1.0, "rule").withAction(Action.MOVE_TO_TRASH));

        assertThat(result).isEqualTo(Action.KEEP);
    }

    @Test
    void aFolderBeatsARulesAction() {
        var result = engine.decide(new Classification(Category.OTHER, 1.0, "rule")
                .withAction(Action.ARCHIVE).withFolder("Ikea"));

        assertThat(result).isEqualTo(Action.MOVE_TO_FOLDER);
    }

    @Test
    void anUnsureRuleActionIsFlaggedForReview() {
        var result = engine.decide(new Classification(Category.OTHER, 0.5, "rule").withAction(Action.MOVE_TO_TRASH));

        assertThat(result).isEqualTo(Action.FLAG_FOR_REVIEW);
    }
}
