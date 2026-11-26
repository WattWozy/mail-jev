package dev.jefe.mailsorter.application.port.in.dto;

public record AccountView(
        String id,           // stable key used by connectSaved
        String service,      // e.g. "gmx", "imap:imap.example.com"
        String username,
        AccountStatus status // ACTIVE | SAVED
) {}
