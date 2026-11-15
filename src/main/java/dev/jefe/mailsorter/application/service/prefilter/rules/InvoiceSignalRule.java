package dev.jefe.mailsorter.application.service.prefilter.rules;

import dev.jefe.mailsorter.application.service.prefilter.Rule;
import dev.jefe.mailsorter.domain.Category;
import dev.jefe.mailsorter.domain.Classification;
import dev.jefe.mailsorter.domain.Email;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class InvoiceSignalRule implements Rule {

    private static final List<String> KEYWORDS =
            List.of("invoice", "receipt", "order confirmation", "factura", "recibo", "confirmación de pedido");

    @Override
    public Optional<Classification> apply(Email email) {
        String subject = email.subject() == null ? "" : email.subject().toLowerCase(Locale.ROOT);
        return KEYWORDS.stream()
                .filter(subject::contains)
                .findFirst()
                .map(k -> new Classification(Category.INVOICE, 0.95, "subject mentions '" + k + "'"));
    }
}