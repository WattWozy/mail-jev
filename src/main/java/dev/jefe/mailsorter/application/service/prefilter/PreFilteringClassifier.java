package dev.jefe.mailsorter.application.service.prefilter;

import dev.jefe.mailsorter.application.port.out.Classifier;
import dev.jefe.mailsorter.domain.model.Classification;
import dev.jefe.mailsorter.domain.model.Email;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class PreFilteringClassifier implements Classifier {

    private final Classifier delegate;
    private final AtomicReference<List<Rule>> rules;

    public PreFilteringClassifier(Classifier delegate, List<Rule> initialRules) {
        this.delegate = delegate;
        this.rules = new AtomicReference<>(List.copyOf(initialRules));
    }

    public void replaceRules(List<? extends Rule> updated) {
        this.rules.set(List.copyOf(updated));
    }

    @Override
    public Classification classify(Email email) {
        for (Rule rule : rules.get()) {
            Optional<Classification> result = rule.apply(email);
            if (result.isPresent()) {
                return result.get();
            }
        }
        return delegate.classify(email);
    }
}