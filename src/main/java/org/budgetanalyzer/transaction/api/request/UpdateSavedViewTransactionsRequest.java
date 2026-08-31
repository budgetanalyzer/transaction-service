package org.budgetanalyzer.transaction.api.request;

import java.util.HashSet;
import java.util.List;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.service.SavedViewConstraints;

/** Request for atomically applying a static saved-view membership delta. */
@Schema(description = "Static saved-view membership additions and removals")
public record UpdateSavedViewTransactionsRequest(
    @ArraySchema(
            maxItems = SavedViewConstraints.MAX_MEMBERSHIP_SIZE,
            arraySchema =
                @Schema(
                    description = "Unordered transaction IDs to add",
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    example = "[123, 456]"),
            schema = @Schema(type = "integer", format = "int64", minimum = "1"))
        @NotNull(message = "Add transaction IDs are required")
        List<@NotNull(message = "Transaction ID is required") @Positive Long> addTransactionIds,
    @ArraySchema(
            maxItems = SavedViewConstraints.MAX_MEMBERSHIP_SIZE,
            arraySchema =
                @Schema(
                    description = "Unordered transaction IDs to remove",
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    example = "[789]"),
            schema = @Schema(type = "integer", format = "int64", minimum = "1"))
        @NotNull(message = "Remove transaction IDs are required")
        List<@NotNull(message = "Transaction ID is required") @Positive Long>
            removeTransactionIds) {

  /** Indicates whether the delta requests at least one addition or removal. */
  @AssertTrue(message = "At least one transaction ID must be added or removed")
  @JsonIgnore
  @Schema(hidden = true)
  public boolean isMembershipChangeRequested() {
    if (addTransactionIds == null || removeTransactionIds == null) {
      return true;
    }
    return !addTransactionIds.isEmpty() || !removeTransactionIds.isEmpty();
  }

  /** Indicates whether the requested additions and removals are disjoint. */
  @AssertTrue(message = "Add and remove transaction IDs must be disjoint")
  @JsonIgnore
  @Schema(hidden = true)
  public boolean isMembershipDeltaDisjoint() {
    if (addTransactionIds == null || removeTransactionIds == null) {
      return true;
    }

    var addTransactionIdSet = new HashSet<>(addTransactionIds);
    return removeTransactionIds.stream().noneMatch(addTransactionIdSet::contains);
  }
}
