package dev.jefe.mailsorter.application.service.prefilter;

import dev.jefe.mailsorter.application.port.out.Classifier;
import dev.jefe.mailsorter.domain.model.Classification;
import dev.jefe.mailsorter.domain.model.ClassificationSource;
import dev.jefe.mailsorter.domain.model.Email;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Runs deterministic rules before the delegate (jev): the account's own rules first, then the built-ins.
 * The first matching rule decides.
 */
public final class PreFilteringClassifier implements Classifier {

    private final Classifier delegate;
    private final List<Rule> builtinRules;
    private final Function<String, List<? extends Rule>> userRulesFor;
    private final Map<String, List<Rule>> rulesByAccount = new ConcurrentHashMap<>();

    public PreFilteringClassifier(Classifier delegate, List<? extends Rule> builtinRules) {
        this(delegate, builtinRules, account -> List.of());
    }

    public PreFilteringClassifier(Classifier delegate, List<? extends Rule> builtinRules,
                                  Function<String, List<? extends Rule>> userRulesFor) {
        this.delegate = delegate;
        this.builtinRules = List.copyOf(builtinRules);
        this.userRulesFor = userRulesFor;
    }

    /** Call when any account's rules change; they are looked up again on next use. */
    public void rulesChanged() {
        rulesByAccount.clear();
    }

    /** Without an account only the built-in rules apply. */
    @Override
    public Classification classify(Email email) {
        return classify(email, builtinRules, null);
    }

    @Override
    public Classification classify(Email email, String accountId) {
        if (accountId == null) {
            return classify(email);
        }
        return classify(email, rulesByAccount.computeIfAbsent(accountId, this::combined), accountId);
    }

    private List<Rule> combined(String accountId) {
        List<? extends Rule> own = userRulesFor.apply(accountId);
        List<Rule> all = new ArrayList<>(own.size() + builtinRules.size());
        all.addAll(own);
        all.addAll(builtinRules);
        return List.copyOf(all);
    }

    private Classification classify(Email email, List<Rule> rules, String accountId) {
        for (Rule rule : rules) {
            Optional<Classification> result = rule.apply(email);
            if (result.isPresent()) {
                return result.get().from(ClassificationSource.RULE);
            }
        }
        return accountId == null ? delegate.classify(email) : delegate.classify(email, accountId);
    }
}
