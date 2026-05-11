package org.budgetanalyzer.transaction.api.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response from batch import containing created transactions and duplicate information.
 *
 * <p>The response includes the count of created and skipped transactions, plus the full list of
 * created transactions with their assigned IDs for UI navigation.
 */
@Schema(description = "Response from batch transaction import")
public record BatchImportResponse(
    @Schema(
            description = "Number of transactions created",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "156")
        int created,
    @Schema(
            description =
                "Number of duplicate rows skipped because allowDuplicate was false or omitted",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "3")
        int duplicatesSkipped,
    @Schema(
            description = "Number of duplicate rows intentionally imported with allowDuplicate",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "1")
        int duplicatesImported,
    @Schema(
            description = "List of created transactions with IDs",
            requiredMode = Schema.RequiredMode.REQUIRED)
        List<TransactionResponse> transactions) {}
