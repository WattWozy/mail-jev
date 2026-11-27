package dev.jefe.mailsorter.application.service;

import dev.jefe.mailsorter.application.port.in.dto.AddRuleCommand;
import dev.jefe.mailsorter.application.port.in.AddRuleUseCase;
import dev.jefe.mailsorter.application.port.in.DeleteRuleUseCase;
import dev.jefe.mailsorter.application.port.in.ListRulesUseCase;
import dev.jefe.mailsorter.application.exception.NotConnectedException;
import dev.jefe.mailsorter.application.port.out.ConnectionStore;
import dev.jefe.mailsorter.application.port.out.MailProvider;
import dev.jefe.mailsorter.application.port.out.RuleRepository;
import dev.jefe.mailsorter.application.service.prefilter.rules.PrefilterRule;
import dev.jefe.mailsorter.domain.model.Action;
import dev.jefe.mailsorter.domain.model.Category;
import dev.jefe.mailsorter.domain.model.FolderNames;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class RuleService implements ListRulesUseCase, AddRuleUseCase, DeleteRuleUseCase {

    private final RuleRepository repository;
    private final ConnectionStore connections;
    private final Runnable onRulesChanged;

    /** Rules are managed for the connected account; {@code onRulesChanged} runs after every change. */
    public RuleService(RuleRepository repository, ConnectionStore connections, Runnable onRulesChanged) {
        this.repository = Objects.requireNonNull(repository);
        this.connections = Objects.requireNonNull(connections);
        this.onRulesChanged = Objects.requireNonNull(onRulesChanged);
    }

    /** The rules of one account, for the classifier. */
    public List<PrefilterRule> rulesFor(String accountId) {
        return repository.findAll(accountId);
    }

    @Override
    public List<PrefilterRule> execute() {
        return repository.findAll(activeAccount());
    }

    @Override
    public PrefilterRule execute(AddRuleCommand command) {
        Objects.requireNonNull(command, "command is required");
        if (command.field() == null) {
            throw new IllegalArgumentException("field is required");
        }
        String folder = FolderNames.validate(command.folder());
        if (folder == null && command.action() == Action.MOVE_TO_FOLDER) {
            throw new IllegalArgumentException("folder is required to move mail to a folder");
        }
        if (folder == null && command.action() == null) {
            throw new IllegalArgumentException("action is required");
        }
        String normalizedPattern = normalize(command.pattern());
        if (normalizedPattern.isEmpty()) {
            throw new IllegalArgumentException("pattern is required");
        }

        Category category = command.category() != null ? command.category() : Category.OTHER;
        PrefilterRule rule = new PrefilterRule(
                UUID.randomUUID().toString(),
                command.field(),
                normalizedPattern,
                folder != null ? Action.MOVE_TO_FOLDER : command.action(),
                category,
                folder
        );

        repository.save(activeAccount(), rule);
        onRulesChanged.run();
        return rule;
    }

    @Override
    public void execute(String ruleId) {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("ruleId is required");
        }
        boolean deleted = repository.deleteById(activeAccount(), ruleId.trim());
        if (!deleted) {
            throw new IllegalArgumentException("Unknown rule: " + ruleId);
        }
        onRulesChanged.run();
    }

    private String activeAccount() {
        return connections.current().map(MailProvider::accountId).orElseThrow(NotConnectedException::new);
    }

    private static String normalize(String pattern) {
        return pattern == null ? "" : pattern.trim().toLowerCase(Locale.ROOT);
    }
}