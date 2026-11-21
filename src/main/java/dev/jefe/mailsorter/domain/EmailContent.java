package dev.jefe.mailsorter.domain;

public record EmailContent(
        String emailId,
        String from,
        String subject,
        String date,
        String textBody       // always plain text
) {}
