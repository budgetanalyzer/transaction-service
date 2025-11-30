package org.budgetanalyzer.transaction.api.request;

import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request object for updating a transaction's mutable fields.
 *
 * <p>Only the free-form fields (description and accountId) can be updated. All other transaction
 * fields are immutable to preserve the integrity of imported financial data.
 */
@Schema(description = "Request to update a transaction's mutable fields")
public record TransactionUpdateRequest(
    @Schema(
            description = "Updated description for the transaction",
            example = "Whole Foods - groceries for dinner party",
            maxLength = 500)
        @Size(max = 500, message = "Description cannot exceed 500 characters")
        String description,
    @Schema(
            description = "Updated account ID to associate with the transaction",
            example = "checking-12345",
            maxLength = 100)
        @Size(max = 100, message = "Account ID cannot exceed 100 characters")
        String accountId) {}
