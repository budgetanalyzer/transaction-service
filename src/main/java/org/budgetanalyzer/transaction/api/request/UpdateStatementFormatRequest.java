package org.budgetanalyzer.transaction.api.request;

import jakarta.validation.constraints.Size;

/**
 * Request DTO for updating an existing statement format.
 *
 * <p>All fields are optional - only non-null fields will be updated. Format type cannot be changed
 * after creation.
 *
 * @param displayName user-friendly display name for UI dropdowns
 * @param bankName bank name for transactions created from this format
 * @param defaultCurrencyIsoCode default currency ISO code for transactions
 * @param enabled whether this format is enabled for use
 */
public record UpdateStatementFormatRequest(
    @Size(max = 100, message = "Display name must be at most 100 characters") String displayName,
    @Size(max = 100, message = "Bank name must be at most 100 characters") String bankName,
    @Size(min = 3, max = 3, message = "Currency ISO code must be exactly 3 characters")
        String defaultCurrencyIsoCode,
    Boolean enabled) {}
