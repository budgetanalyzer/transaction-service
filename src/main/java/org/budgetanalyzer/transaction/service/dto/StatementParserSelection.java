package org.budgetanalyzer.transaction.service.dto;

import org.budgetanalyzer.transaction.domain.ParserRevision;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.service.extractor.StatementExtractor;

/** Selected parser revision and executable extractor for a statement format. */
public record StatementParserSelection(
    StatementFormat statementFormat,
    ParserRevision parserRevision,
    StatementExtractor statementExtractor) {}
