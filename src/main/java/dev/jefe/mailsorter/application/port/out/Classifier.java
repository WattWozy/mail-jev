package dev.jefe.mailsorter.application.port.out;

import dev.jefe.mailsorter.domain.model.Classification;
import dev.jefe.mailsorter.domain.model.Email;

/** Driven port. The "jev" adapter implements this. */
public interface Classifier {
    Classification classify(Email email);

    /**
     * Classifies mail of one account, so account-specific rules and folders can apply.
     * Classifiers without per-account behaviour ignore the account.
     */
    default Classification classify(Email email, String accountId) {
        return classify(email);
    }
}
