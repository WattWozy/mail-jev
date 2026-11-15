package dev.jefe.mailsorter.domain;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public record Email(String id, String from, String subject, String snippet, Map<String, String> headers) {

    public Email {
        var normalized = new LinkedHashMap<String, String>();
        if (headers != null) {
            headers.forEach((k, v) -> normalized.putIfAbsent(k.toLowerCase(Locale.ROOT), v));
        }
        headers = Map.copyOf(normalized);
    }

    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
    }
}