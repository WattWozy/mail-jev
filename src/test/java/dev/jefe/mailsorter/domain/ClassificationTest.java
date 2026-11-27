package dev.jefe.mailsorter.domain;

import dev.jefe.mailsorter.domain.model.Action;
import dev.jefe.mailsorter.domain.model.Category;
import dev.jefe.mailsorter.domain.model.Classification;
import dev.jefe.mailsorter.domain.model.ClassificationSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClassificationTest {

    @Test
    void hasNoFolderByDefault() {
        assertThat(new Classification(Category.OTHER, 1.0, "x").folder()).isNull();
    }

    @Test
    void retaggingTheSourceKeepsTheFolder() {
        var c = new Classification(Category.OTHER, 1.0, "x").withFolder("Ikea").from(ClassificationSource.RULE);

        assertThat(c.folder()).isEqualTo("Ikea");
        assertThat(c.source()).isEqualTo(ClassificationSource.RULE);
    }

    @Test
    void aBlankFolderMeansNoFolder() {
        assertThat(new Classification(Category.OTHER, 1.0, "x").withFolder("  ").folder()).isNull();
    }

    @Test
    void theFolderNameIsTrimmed() {
        assertThat(new Classification(Category.OTHER, 1.0, "x").withFolder(" Ikea ").folder()).isEqualTo("Ikea");
    }

    @Test
    void stillRejectsAnOutOfRangeConfidence() {
        assertThatThrownBy(() -> new Classification(Category.OTHER, 1.1, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hasNoActionByDefaultAndRetaggingKeepsOne() {
        assertThat(new Classification(Category.OTHER, 1.0, "x").action()).isNull();

        var c = new Classification(Category.OTHER, 1.0, "x").withAction(Action.ARCHIVE).from(ClassificationSource.RULE);
        assertThat(c.action()).isEqualTo(Action.ARCHIVE);
    }

    @Test
    void moveToFolderIsNotAnActionOnItsOwnTheFolderSaysThat() {
        assertThat(new Classification(Category.OTHER, 1.0, "x").withAction(Action.MOVE_TO_FOLDER).action()).isNull();
    }
}
