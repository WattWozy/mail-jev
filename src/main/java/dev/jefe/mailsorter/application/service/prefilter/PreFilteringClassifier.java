package dev.jefe.mailsorter.application.service.prefilter;

import dev.jefe.mailsorter.application.port.out.Classifier;
import dev.jefe.mailsorter.domain.Classification;
import dev.jefe.mailsorter.domain.Email;

import java.util.List;
import java.util.Optional;

public final class PreFilteringClassifier implements Classifier {

    private final Classifier delegate;
    private final List<Rule> rules; // order matters: first match wins

    public PreFilteringClassifier(Classifier delegate, List<Rule> rules) {
        this.delegate = delegate;
        this.rules = List.copyOf(rules);
    }

    @Override
    public Classification classify(Email email) {
        for (Rule rule : rules) {
            Optional<Classification> result = rule.apply(email);
            if (result.isPresent()) {
                return result.get();
            }
        }
        return delegate.classify(email);
    }
}