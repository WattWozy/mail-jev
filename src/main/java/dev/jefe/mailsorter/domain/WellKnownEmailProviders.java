package dev.jefe.mailsorter.domain;

import dev.jefe.mailsorter.domain.config.MailboxConfig;

/**
 * Convenient presets for the most common email providers.
 * All of these use IMAP over SSL/TLS (port 993).
 *
 * Notes:
 * - Most modern providers require an <b>app password</b> (or OAuth)
 *   instead of the regular account password.
 * - Some providers (Outlook, GMX, …) require you to explicitly enable
 *   IMAP access in the web interface first.
 */
public final class WellKnownEmailProviders {

    private WellKnownEmailProviders() {
        // utility class
    }

    // ------------------------------------------------------------------
    // GMX
    // ------------------------------------------------------------------

    public static MailboxConfig gmx(String email, String secret) {
        return MailboxConfig.builder()
                .providerType("imap")
                .host("imap.gmx.com")
                .port(993)
                .username(email)
                .secret(secret)
                .option("ssl", "true")
                .build();
    }

    // ------------------------------------------------------------------
    // Gmail
    // ------------------------------------------------------------------

    /**
     * Gmail IMAP.
     * Requires an App Password (or OAuth). Regular Google password no longer works.
     */
    public static MailboxConfig gmail(String email, String secret) {
        return MailboxConfig.builder()
                .providerType("imap")
                .host("imap.gmail.com")
                .port(993)
                .username(email)
                .secret(secret)
                .option("ssl", "true")
                .build();
    }

    // ------------------------------------------------------------------
    // Outlook / Hotmail / Live / Office 365
    // ------------------------------------------------------------------

    /**
     * Outlook.com / Hotmail / Live / Microsoft 365.
     * Modern Auth (OAuth2) is preferred. Basic auth is heavily restricted.
     * IMAP access may need to be enabled in the Outlook web settings.
     */
    public static MailboxConfig outlook(String email, String secret) {
        return MailboxConfig.builder()
                .providerType("imap")
                .host("outlook.office365.com")
                .port(993)
                .username(email)
                .secret(secret)
                .option("ssl", "true")
                .build();
    }

    // ------------------------------------------------------------------
    // Yahoo
    // ------------------------------------------------------------------

    /**
     * Yahoo Mail.
     * Requires an App Password.
     */
    public static MailboxConfig yahoo(String email, String secret) {
        return MailboxConfig.builder()
                .providerType("imap")
                .host("imap.mail.yahoo.com")
                .port(993)
                .username(email)
                .secret(secret)
                .option("ssl", "true")
                .build();
    }

    // ------------------------------------------------------------------
    // iCloud
    // ------------------------------------------------------------------

    /**
     * iCloud Mail.
     * Requires an App-Specific Password generated at account.apple.com.
     * Username is usually the local part only (before @). Full address is a fallback.
     */
    public static MailboxConfig icloud(String email, String secret) {
        // Extract local part as Apple often expects it for IMAP
        String username = email.contains("@")
                ? email.substring(0, email.indexOf('@'))
                : email;

        return MailboxConfig.builder()
                .providerType("imap")
                .host("imap.mail.me.com")
                .port(993)
                .username(username)
                .secret(secret)
                .option("ssl", "true")
                // keep the original full address available if needed later
                .option("fullEmail", email)
                .build();
    }

    // ------------------------------------------------------------------
    // Optional helpers
    // ------------------------------------------------------------------

    /**
     * Convenience overload that lets the caller force the full email as username
     * (useful when the local-part-only version fails for iCloud).
     */
    public static MailboxConfig icloudFullUsername(String email, String secret) {
        return MailboxConfig.builder()
                .providerType("imap")
                .host("imap.mail.me.com")
                .port(993)
                .username(email)
                .secret(secret)
                .option("ssl", "true")
                .build();
    }
}