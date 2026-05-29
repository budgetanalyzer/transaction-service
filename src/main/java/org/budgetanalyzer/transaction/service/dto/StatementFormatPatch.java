package org.budgetanalyzer.transaction.service.dto;

/**
 * Service-layer patch for updating a statement format.
 *
 * <p>All fields are optional - only non-null fields will be updated.
 *
 * @param displayName user-friendly display name
 * @param bankName bank name for transactions created from this format
 * @param defaultCurrencyIsoCode default currency ISO code
 * @param enabled whether this format is enabled for use
 */
public record StatementFormatPatch(
    String displayName, String bankName, String defaultCurrencyIsoCode, Boolean enabled) {}
