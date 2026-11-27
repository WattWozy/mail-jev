package dev.jefe.mailsorter;

import dev.jefe.mailsorter.application.port.out.ActivityRepository;
import dev.jefe.mailsorter.application.port.out.AuditLog;
import dev.jefe.mailsorter.application.port.out.AutopilotSettingsStore;
import dev.jefe.mailsorter.application.port.out.Classifier;
import dev.jefe.mailsorter.application.port.out.CredentialStore;
import dev.jefe.mailsorter.application.port.out.FolderRepository;
import dev.jefe.mailsorter.application.port.out.RuleRepository;
import dev.jefe.mailsorter.application.service.*;
import dev.jefe.mailsorter.application.service.prefilter.PreFilteringClassifier;
import dev.jefe.mailsorter.application.service.prefilter.Rule;
import dev.jefe.mailsorter.application.service.prefilter.rules.InvoiceSignalRule;
import dev.jefe.mailsorter.domain.model.Action;
import dev.jefe.mailsorter.domain.model.Category;
import dev.jefe.mailsorter.domain.policy.PolicyEngine;
import dev.jefe.mailsorter.infra.crypto.AesGcmSecretCipher;
import dev.jefe.mailsorter.infra.jev.JevClassifier;
import dev.jefe.mailsorter.infra.jev.JevClient;
import dev.jefe.mailsorter.infra.jev.JevConfig;
import dev.jefe.mailsorter.infra.mail.ImapMailProviderFactory;
import dev.jefe.mailsorter.infra.persistence.memory.*;
import dev.jefe.mailsorter.infra.persistence.sqlite.SqliteActivityRepository;
import dev.jefe.mailsorter.infra.persistence.sqlite.SqliteAuditLog;
import dev.jefe.mailsorter.infra.persistence.sqlite.SqliteAutopilotSettingsStore;
import dev.jefe.mailsorter.infra.scheduling.ExecutorScheduler;
import dev.jefe.mailsorter.infra.persistence.sqlite.SqliteCredentialStore;
import dev.jefe.mailsorter.infra.persistence.sqlite.SqliteRuleStore;
import dev.jefe.mailsorter.infra.persistence.sqlite.SqliteFolderStore;
import dev.jefe.mailsorter.infra.persistence.sqlite.SqliteDatabase;
import dev.jefe.mailsorter.infra.web.SseBroadcaster;
import dev.jefe.mailsorter.infra.web.WebServer;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class CompositionRoot {

    private CompositionRoot() {}

    public static WebServer build(AppConfig config) throws IOException {

        //inject depending on the profile (sql/inmem)
        AuditLog auditLog;
        CredentialStore credentialStore;
        RuleRepository ruleRepository;
        AccountService accountService;
        AutopilotSettingsStore autopilotSettings;
        ActivityRepository activityRepository;
        FolderRepository folderRepository;

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
            ruleRepository = new SqliteRuleStore(db);
            autopilotSettings = new SqliteAutopilotSettingsStore(db);
            activityRepository = new SqliteActivityRepository(db);
            folderRepository = new SqliteFolderStore(db);
        } else {
            auditLog = new InMemoryAuditLog();
            credentialStore = new InMemoryCredentialStore();
            accountService = new AccountService(
                    new ImapMailProviderFactory(),
                    store,
                    credentialStore);
            ruleRepository = new InMemoryRuleStore();
            autopilotSettings = new InMemoryAutopilotSettingsStore();
            activityRepository = new InMemoryActivityRepository();
            folderRepository = new InMemoryFolderStore();
        }

        var planStore = new InMemoryPlanStore();

        List<Rule> builtinRules = List.of(new InvoiceSignalRule()); // + List-Unsubscribe rule when ready

        var jev = new JevClassifier(
                new JevClient(new JevConfig(config.jevEndpoint(), config.jevApiKey(), Duration.ofSeconds(10))),
                500,
                folderRepository::findAll);   // each account's folders are offered to jev as extra choices

        // Rules are per account: the classifier asks for the account's own rules (first) on demand.
        var preFilteringClassifier = new PreFilteringClassifier(jev, builtinRules, ruleRepository::findAll);
        Classifier classifier = preFilteringClassifier;

        var policy = new PolicyEngine(Map.of(
                Category.NEWSLETTER, Action.ARCHIVE,
                Category.SOCIAL, Action.ARCHIVE,
                Category.PROMOTION, Action.MOVE_TO_TRASH,
                Category.SPAM, Action.MOVE_TO_TRASH), 0.9);

        var classifyService = new ClassifyService(store, classifier, policy, planStore);

        var ruleService = new RuleService(ruleRepository, folderRepository, store, () -> {
            preFilteringClassifier.rulesChanged();
            classifyService.forgetCachedClassifications(); // a new rule must apply to mail already seen
        });

        // Step 3 hands the folders to jev; until then they are only stored.
        var folderService = new FolderService(folderRepository, ruleRepository, store, () -> {
            jev.foldersChanged();
            classifyService.forgetCachedClassifications(); // a new folder must apply to mail already seen
        });
        var applyService = new ApplyPlanService(store, planStore, auditLog, activityRepository, Clock.systemUTC());
        var events = new SseBroadcaster();

        var autopilot = new AutopilotService(
                classifyService, applyService, accountService,
                autopilotSettings, new ExecutorScheduler(), events, Clock.systemUTC());

        // Autopilot left on at last shutdown keeps going (reconnects the saved account on its first run)
        autopilot.resumeIfEnabled();

        return new WebServer(
                config.webHost(),
                config.webPort(),
                accountService,
                classifyService,
                applyService,
                new GetEmailService(store),
                ruleService,
                folderService,
                autopilot,
                new ActivityService(activityRepository, store),
                events
        );
    }
}