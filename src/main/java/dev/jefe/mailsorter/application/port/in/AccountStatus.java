package dev.jefe.mailsorter.application.port.in;

public enum AccountStatus {
    ACTIVE,   // currently connected session
    SAVED     // credentials exist but not the live one
}
