package dev.jefe.mailsorter.infra.persistence.sqlite;

import dev.jefe.mailsorter.application.service.prefilter.rules.PrefilterRule;
import dev.jefe.mailsorter.application.service.prefilter.rules.RuleField;
import dev.jefe.mailsorter.domain.model.Action;
import dev.jefe.mailsorter.domain.model.Category;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteRuleStoreTest {

    @TempDir Path dir;
    private SqliteRuleStore store;

    @BeforeEach
    void setUp() {
        var db = new SqliteDatabase(dir.resolve("rules.db"));
        db.migrate();
        store = new SqliteRuleStore(db);
    }

    @Test
    void roundTripsAFolderRule() {
        var rule = new PrefilterRule("r1", RuleField.FROM_DOMAIN, "ikea.com", Action.MOVE_TO_FOLDER,
                Category.OTHER, "Shops/Ikea");

        store.save(rule);

        assertThat(store.findAll()).containsExactly(rule);
    }

    @Test
    void roundTripsARuleWithoutAFolder() {
        var rule = new PrefilterRule("r1", RuleField.SUBJECT_CONTAINS, "invoice", Action.KEEP,
                Category.INVOICE, null);

        store.save(rule);

        assertThat(store.findAll()).containsExactly(rule);
    }

    @Test
    void keepsInsertionOrderSoTheFirstMatchingRuleWins() {
        var first = new PrefilterRule("a", RuleField.FROM_DOMAIN, "ikea.com", Action.MOVE_TO_FOLDER, Category.OTHER, "Ikea");
        var second = new PrefilterRule("b", RuleField.SUBJECT_CONTAINS, "sofa", Action.ARCHIVE, Category.OTHER, null);

        store.save(first);
        store.save(second);

        assertThat(store.findAll()).containsExactly(first, second);
    }

    @Test
    void deletesById() {
        store.save(new PrefilterRule("r1", RuleField.FROM_DOMAIN, "ikea.com", Action.MOVE_TO_FOLDER, Category.OTHER, "Ikea"));

        assertThat(store.deleteById("r1")).isTrue();
        assertThat(store.findAll()).isEmpty();
    }
}
