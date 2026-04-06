package org.budgetanalyzer.transaction.api.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.service.api.PageMetadataResponse;
import org.budgetanalyzer.service.api.PagedResponse;

/**
 * Concrete OpenAPI wrapper for paged admin transaction search results.
 *
 * <p>This record mirrors {@link PagedResponse} while binding the content type to {@link
 * AdminTransactionResponse}. Springdoc does not emit a usable schema for the generic wrapper on the
 * admin search endpoint, so this concrete type is used for documentation.
 *
 * @param content admin transaction items included in the current page
 * @param metadata pagination metadata for the current result set
 */
@Schema(description = "Paged admin transaction search response")
public record AdminTransactionSearchResponse(
    @Schema(
            description = "Admin transaction items included in the current page",
            requiredMode = Schema.RequiredMode.REQUIRED)
        List<AdminTransactionResponse> content,
    @Schema(
            description = "Pagination metadata for the current result set",
            requiredMode = Schema.RequiredMode.REQUIRED)
        PageMetadataResponse metadata) {}
