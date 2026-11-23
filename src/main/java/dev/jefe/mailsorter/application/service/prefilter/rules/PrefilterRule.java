package dev.jefe.mailsorter.application.service.prefilter.rules;

import dev.jefe.mailsorter.application.service.prefilter.Rule;
import dev.jefe.mailsorter.domain.Action;
import dev.jefe.mailsorter.domain.Category;
import dev.jefe.mailsorter.domain.Classification;
import dev.jefe.mailsorter.domain.Email;

import java.util.Locale;
import java.util.Optional;

public record PrefilterRule(
        String id,
        RuleField field,
        String pattern,
        Action action,
        Category category
) implements Rule {
    public Optional<Classification> apply(Email email) {
        if (!matches(email)) {
            return Optional.empty();
        }
        String reason = "Matched rule: " + field.name() + " = " + pattern;
        return Optional.of(new Classification(category, 1.0, reason));
    }

    private boolean matches(Email email) {
        String target = pattern.toLowerCase(Locale.ROOT);
        return switch (field) {
            case FROM_ADDRESS -> email.from() != null
                    && email.from().toLowerCase(Locale.ROOT).contains(target);
            case FROM_DOMAIN -> extractDomain(email.from())
                    .map(d -> d.equals(target) || d.endsWith("." + target))
                    .orElse(false);
            case SUBJECT_CONTAINS -> email.subject() != null
                    && email.subject().toLowerCase(Locale.ROOT).contains(target);
        };
    }

    private static Optional<String> extractDomain(String from) {
        if (from == null) return Optional.empty();
        int at = from.lastIndexOf('@');
        if (at < 0 || at == from.length() - 1) return Optional.empty();
        String domain = from.substring(at + 1).replace(">", "").trim().toLowerCase(Locale.ROOT);
        return domain.isEmpty() ? Optional.empty() : Optional.of(domain);
    }
}
