package dev.jefe.mailsorter.application.port.in;

public record ClassifyCommand(int limit, boolean dryRun) {

    public ClassifyCommand {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
    }

    /** Safe default: decide and audit, but never touch the mailbox. */
    public static ClassifyCommand preview(int limit) { return new ClassifyCommand(limit, true); }

    public static ClassifyCommand apply(int limit) { return new ClassifyCommand(limit, false); }
}
