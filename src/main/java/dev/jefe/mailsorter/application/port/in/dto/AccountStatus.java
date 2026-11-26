package dev.jefe.mailsorter.application.port.in.dto;

public enum AccountStatus {
    ACTIVE,   // currently connected session
    SAVED     // credentials exist but not the live one
}
