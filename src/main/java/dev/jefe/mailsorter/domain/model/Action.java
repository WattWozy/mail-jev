package dev.jefe.mailsorter.domain.model;

/** "Erase" is never a hard delete: the strongest action is MOVE_TO_TRASH. */
public enum Action { KEEP, FLAG_FOR_REVIEW, ARCHIVE, MOVE_TO_TRASH }
