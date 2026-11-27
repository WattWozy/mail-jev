package dev.jefe.mailsorter.domain.model;

/**
 * Rules for user folder names. Names keep the user's spelling; "/" nests folders (Gmail shows it as a nested label).
 */
public final class FolderNames {

    public static final int MAX_LENGTH = 100;

    private FolderNames() {}

    /** Returns the trimmed name, null when blank, or throws when the name is unsafe to send to a mail server. */
    public static String validate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String name = raw.trim();
        if (name.length() > MAX_LENGTH) {
            throw invalid(name, "is longer than " + MAX_LENGTH + " characters");
        }
        if (name.equalsIgnoreCase("INBOX")) {
            throw invalid(name, "is the inbox itself");
        }
        if (name.startsWith("/") || name.endsWith("/") || name.contains("//")) {
            throw invalid(name, "has an empty folder level");
        }
        if (name.contains("*") || name.contains("%")) {
            throw invalid(name, "contains an IMAP wildcard (* or %)");
        }
        if (name.chars().anyMatch(Character::isISOControl)) {
            throw invalid(name, "contains control characters");
        }
        return name;
    }

    private static IllegalArgumentException invalid(String name, String why) {
        return new IllegalArgumentException("invalid folder '" + name + "': " + why);
    }
}
