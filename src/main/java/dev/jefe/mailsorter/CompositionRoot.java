package dev.jefe.mailsorter;

import dev.jefe.mailsorter.application.port.out.AuditLog;
import dev.jefe.mailsorter.application.port.out.Classifier;
import dev.jefe.mailsorter.application.port.out.CredentialStore;
import dev.jefe.mailsorter.application.service.AccountService;
import dev.jefe.mailsorter.application.service.ApplyPlanService;
import dev.jefe.mailsorter.application.service.ClassifyService;
import dev.jefe.mailsorter.application.service.GetEmailService;
import dev.jefe.mailsorter.application.service.prefilter.PreFilteringClassifier;
import dev.jefe.mailsorter.application.service.prefilter.rules.InvoiceSignalRule;
import dev.jefe.mailsorter.domain.Action;
import dev.jefe.mailsorter.domain.Category;
import dev.jefe.mailsorter.domain.PolicyEngine;
import dev.jefe.mailsorter.infra.crypto.AesGcmSecretCipher;
import dev.jefe.mailsorter.infra.jev.JevClassifier;
import dev.jefe.mailsorter.infra.jev.JevClient;
import dev.jefe.mailsorter.infra.jev.JevConfig;
import dev.jefe.mailsorter.infra.mail.ImapMailProviderFactory;
import dev.jefe.mailsorter.infra.memory.inMemory.InMemoryAuditLog;
import dev.jefe.mailsorter.infra.memory.inMemory.InMemoryConnectionStore;
import dev.jefe.mailsorter.infra.memory.inMemory.InMemoryCredentialStore;
import dev.jefe.mailsorter.infra.memory.inMemory.InMemoryPlanStore;
import dev.jefe.mailsorter.infra.memory.sqlite.SqliteAuditLog;
import dev.jefe.mailsorter.infra.memory.sqlite.SqliteCredentialStore;
import dev.jefe.mailsorter.infra.sqlite.SqliteDatabase;
import dev.jefe.mailsorter.infra.web.WebServer;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class CompositionRoot {

    private CompositionRoot() {}

    public static WebServer build(AppConfig config) throws IOException {

        //inject depending on the profile (sql/inmem)
        AuditLog auditLog;
        CredentialStore credentialStore;
        AccountService accountService;

        var store = new InMemoryConnectionStore();
        var cipher = new AesGcmSecretCipher(config.storeSecretKey());

        if (config.persistence() == AppConfig.PersistenceMode.SQLITE) {
            var db = new SqliteDatabase(config.dbPath());
            db.migrate();
            auditLog = new SqliteAuditLog(db);
            credentialStore = new SqliteCredentialStore(db, cipher);
            accountService = new AccountService(
                    new ImapMailProviderFactory(),
                    store,
                    credentialStore);
        } else {
            auditLog = new InMemoryAuditLog();
            credentialStore = new InMemoryCredentialStore();
            accountService = new AccountService(
                    new ImapMailProviderFactory(),
                    store,
                    credentialStore);
        }

        var planStore = new InMemoryPlanStore();

        var jev = new JevClassifier(
                new JevClient(new JevConfig(config.jevEndpoint(), config.jevApiKey(), Duration.ofSeconds(10))), 500);
        // order matters: the invoice signal must come before List-Unsubscribe
        Classifier classifier = new PreFilteringClassifier(jev, List.of(new InvoiceSignalRule()));

        var policy = new PolicyEngine(Map.of(
                Category.NEWSLETTER, Action.ARCHIVE,
                Category.SOCIAL, Action.ARCHIVE,
                Category.PROMOTION, Action.MOVE_TO_TRASH,
                Category.SPAM, Action.MOVE_TO_TRASH), 0.9);

        return new WebServer(
                config.webHost(),
                config.webPort(),
                accountService,
                new ClassifyService(store, classifier, policy, planStore),
                new ApplyPlanService(store, planStore, auditLog),
                new GetEmailService(store)
        );
    }
}