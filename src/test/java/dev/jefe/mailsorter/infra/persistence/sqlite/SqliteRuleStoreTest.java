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

    private static final String GMX = "gmx|me@gmx.com";
    private static final String GMAIL = "gmail|me@gmail.com";

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

        store.save(GMX, rule);

        assertThat(store.findAll(GMX)).containsExactly(rule);
    }

    @Test
    void roundTripsARuleWithoutAFolder() {
        var rule = new PrefilterRule("r1", RuleField.SUBJECT_CONTAINS, "invoice", Action.KEEP,
                Category.INVOICE, null);

        store.save(GMX, rule);

        assertThat(store.findAll(GMX)).containsExactly(rule);
    }

    @Test
    void keepsInsertionOrderSoTheFirstMatchingRuleWins() {
        var first = new PrefilterRule("a", RuleField.FROM_DOMAIN, "ikea.com", Action.MOVE_TO_FOLDER, Category.OTHER, "Ikea");
        var second = new PrefilterRule("b", RuleField.SUBJECT_CONTAINS, "sofa", Action.ARCHIVE, Category.OTHER, null);

        store.save(GMX, first);
        store.save(GMX, second);

        assertThat(store.findAll(GMX)).containsExactly(first, second);
    }

    @Test
    void deletesById() {
        store.save(GMX, new PrefilterRule("r1", RuleField.FROM_DOMAIN, "ikea.com", Action.MOVE_TO_FOLDER, Category.OTHER, "Ikea"));

        assertThat(store.deleteById(GMX, "r1")).isTrue();
        assertThat(store.findAll(GMX)).isEmpty();
    }

    @Test
    void accountsDoNotSeeEachOthersRules() {
        var gmx = new PrefilterRule("a", RuleField.FROM_DOMAIN, "ikea.com", Action.MOVE_TO_FOLDER, Category.OTHER, "Ikea");
        var gmail = new PrefilterRule("b", RuleField.FROM_DOMAIN, "ikea.com", Action.ARCHIVE, Category.OTHER, null);
        store.save(GMX, gmx);
        store.save(GMAIL, gmail);

        assertThat(store.findAll(GMX)).containsExactly(gmx);
        assertThat(store.findAll(GMAIL)).containsExactly(gmail);
        assertThat(store.deleteById(GMAIL, "a")).isFalse();
    }
}
