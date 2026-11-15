package dev.jefe.mailsorter.infra.jev;

import java.net.URI;
import java.time.Duration;

public record JevConfig(URI endpoint, String apiKey, Duration timeout) {}