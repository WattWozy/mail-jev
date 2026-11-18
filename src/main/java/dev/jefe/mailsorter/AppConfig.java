package dev.jefe.mailsorter;

import java.net.URI;

public record AppConfig(String webHost, int webPort, URI jevEndpoint, String jevApiKey) {}