package dev.jefe.mailsorter;

import dev.jefe.mailsorter.application.port.out.Classifier;
import dev.jefe.mailsorter.application.service.ClassifyService;
import dev.jefe.mailsorter.application.service.ConnectService;
import dev.jefe.mailsorter.application.service.prefilter.PreFilteringClassifier;
import dev.jefe.mailsorter.application.service.prefilter.rules.InvoiceSignalRule;
import dev.jefe.mailsorter.application.service.prefilter.rules.ListUnsubscribeRule;
import dev.jefe.mailsorter.domain.Action;
import dev.jefe.mailsorter.domain.Category;
import dev.jefe.mailsorter.domain.PolicyEngine;
import dev.jefe.mailsorter.infra.jev.JevClassifier;
import dev.jefe.mailsorter.infra.jev.JevClient;
import dev.jefe.mailsorter.infra.jev.JevConfig;
import dev.jefe.mailsorter.infra.mail.ImapMailProviderFactory;
import dev.jefe.mailsorter.infra.memory.InMemoryAuditLog;
import dev.jefe.mailsorter.infra.memory.InMemoryConnectionStore;
import dev.jefe.mailsorter.infra.web.WebServer;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class CompositionRoot {

    private CompositionRoot() {}

    public static WebServer build(AppConfig config) throws IOException {
        var store = new InMemoryConnectionStore();
        var audit = new InMemoryAuditLog();

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
                new ConnectService(new ImapMailProviderFactory(), store),
                new ClassifyService(
                        store,
                        classifier,
                        policy,
                        audit));
    }
}