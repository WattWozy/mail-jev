package dev.jefe.mailsorter.domain.model;

/** Where a classification came from. */
public enum ClassificationSource {
    /** A deterministic prefilter rule (built-in or user-defined). */
    RULE,
    /** The ML classifier. */
    MODEL,
    /** Classification failed; a safe placeholder was used. */
    FALLBACK
}
