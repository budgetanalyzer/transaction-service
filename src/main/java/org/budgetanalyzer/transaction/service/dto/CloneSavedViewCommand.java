package org.budgetanalyzer.transaction.service.dto;

/** Service command for cloning a saved view under a new name. */
public record CloneSavedViewCommand(String name) {}
