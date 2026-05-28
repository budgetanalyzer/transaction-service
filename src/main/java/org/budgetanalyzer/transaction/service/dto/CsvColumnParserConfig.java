package org.budgetanalyzer.transaction.service.dto;

/** Deterministic CSV parser configuration stored on a parser revision. */
public record CsvColumnParserConfig(
    String dateHeader,
    String dateFormat,
    String descriptionHeader,
    String creditHeader,
    String debitHeader,
    String typeHeader,
    String categoryHeader) {}
