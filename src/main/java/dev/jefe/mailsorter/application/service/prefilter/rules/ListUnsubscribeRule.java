package dev.jefe.mailsorter.application.service.prefilter.rules;

import dev.jefe.mailsorter.application.service.prefilter.Rule;
import dev.jefe.mailsorter.domain.model.Category;
import dev.jefe.mailsorter.domain.model.Classification;
import dev.jefe.mailsorter.domain.model.Email;

import java.util.Optional;

public final class ListUnsubscribeRule implements Rule {

    @Override
    public Optional<Classification> apply(Email email) {
        return email.header("List-Unsubscribe")
                .map(h -> new Classification(Category.NEWSLETTER, 0.95, "List-Unsubscribe header present"));
    }
}