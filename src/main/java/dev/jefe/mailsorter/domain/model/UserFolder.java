package dev.jefe.mailsorter.domain.model;

import java.util.Objects;

/**
 * A folder the user wants mail sorted into. jev decides by the {@code description}, so it should say in one
 * sentence what belongs there (e.g. "Flight, hotel and holiday deals").
 */
public record UserFolder(String id, String name, String description) {

    public static final int MAX_DESCRIPTION = 300;

    public UserFolder {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(name, "name is required");
        Objects.requireNonNull(description, "description is required");
    }
}
