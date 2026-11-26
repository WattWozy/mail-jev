package dev.jefe.mailsorter.application.service;

import dev.jefe.mailsorter.application.port.in.dto.AddRuleCommand;
import dev.jefe.mailsorter.application.port.in.AddRuleUseCase;
import dev.jefe.mailsorter.application.port.in.DeleteRuleUseCase;
import dev.jefe.mailsorter.application.port.in.ListRulesUseCase;
import dev.jefe.mailsorter.application.port.out.RuleRepository;
import dev.jefe.mailsorter.application.service.prefilter.rules.PrefilterRule;
import dev.jefe.mailsorter.domain.model.Category;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

public final class RuleService implements ListRulesUseCase, AddRuleUseCase, DeleteRuleUseCase {

    private final RuleRepository repository;
    private final Consumer<List<PrefilterRule>> onRulesChanged;

    public RuleService(RuleRepository repository, Consumer<List<PrefilterRule>> onRulesChanged) {
        this.repository = Objects.requireNonNull(repository);
        this.onRulesChanged = Objects.requireNonNull(onRulesChanged);
    }

    /** Call once in CompositionRoot after constructing the classifier. */
    public void reload() {
        onRulesChanged.accept(repository.findAll());
    }

    @Override
    public List<PrefilterRule> execute() {
        return repository.findAll();
    }

    @Override
    public PrefilterRule execute(AddRuleCommand command) {
        Objects.requireNonNull(command, "command is required");
        if (command.field() == null) {
            throw new IllegalArgumentException("field is required");
        }
        if (command.action() == null) {
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
                command.action(),
                category
        );

        repository.save(rule);
        reload();
        return rule;
    }

    @Override
    public void execute(String ruleId) {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("ruleId is required");
        }
        boolean deleted = repository.deleteById(ruleId.trim());
        if (!deleted) {
            throw new IllegalArgumentException("Unknown rule: " + ruleId);
        }
        reload();
    }

    private static String normalize(String pattern) {
        return pattern == null ? "" : pattern.trim().toLowerCase(Locale.ROOT);
    }
}