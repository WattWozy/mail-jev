package dev.jefe.mailsorter.domain.model;

public record EmailContent(
        String emailId,
        String from,
        String subject,
        String date,
        String textBody       // always plain text
) {}
