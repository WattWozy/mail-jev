package dev.jefe.mailsorter.domain.model;

/**
 * "Erase" is never a hard delete: the strongest action is MOVE_TO_TRASH.
 * MOVE_TO_FOLDER files the email into the user folder named by its {@link Classification#folder()}.
 */
public enum Action { KEEP, FLAG_FOR_REVIEW, ARCHIVE, MOVE_TO_FOLDER, MOVE_TO_TRASH }
