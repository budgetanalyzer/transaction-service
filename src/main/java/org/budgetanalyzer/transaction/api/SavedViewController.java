package org.budgetanalyzer.transaction.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.budgetanalyzer.service.api.ApiErrorResponse;
import org.budgetanalyzer.service.security.SecurityContextUtil;
import org.budgetanalyzer.transaction.api.request.CreateSavedViewRequest;
import org.budgetanalyzer.transaction.api.request.UpdateSavedViewRequest;
import org.budgetanalyzer.transaction.api.response.SavedViewResponse;
import org.budgetanalyzer.transaction.api.response.ViewMembershipResponse;
import org.budgetanalyzer.transaction.service.SavedViewService;

/** REST controller for managing saved views (smart collections). */
@Tag(name = "Saved Views", description = "Create and manage saved transaction views")
@RestController
@RequestMapping(path = "/v1/views")
public class SavedViewController {

  private static final Logger log = LoggerFactory.getLogger(SavedViewController.class);

  private final SavedViewService savedViewService;

  public SavedViewController(SavedViewService savedViewService) {
    this.savedViewService = savedViewService;
  }

  @PreAuthorize("hasAuthority('transactions:write')")
  @Operation(
      summary = "Create a saved view",
      description = "Creates a new saved view for the current user")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "201",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = SavedViewResponse.class))),
        @ApiResponse(
            responseCode = "400",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
      })
  @PostMapping(consumes = "application/json", produces = "application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public SavedViewResponse createView(@Valid @RequestBody CreateSavedViewRequest request) {
    var userId = getCurrentUserId();
    log.info("Creating saved view '{}' for user {}", request.name(), userId);

    var view = savedViewService.createView(userId, request);
    var transactionCount = savedViewService.countViewTransactions(view);
    return SavedViewResponse.from(view, transactionCount);
  }

  @PreAuthorize("hasAuthority('transactions:read')")
  @Operation(
      summary = "List saved views",
      description = "Gets all saved views for the current user")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    array =
                        @ArraySchema(schema = @Schema(implementation = SavedViewResponse.class))))
      })
  @GetMapping(produces = "application/json")
  public List<SavedViewResponse> listViews() {
    var userId = getCurrentUserId();
    log.info("Listing saved views for user {}", userId);

    var views = savedViewService.getViewsForUser(userId);
    return views.stream()
        .map(view -> SavedViewResponse.from(view, savedViewService.countViewTransactions(view)))
        .toList();
  }

  @PreAuthorize("hasAuthority('transactions:read')")
  @Operation(summary = "Get a saved view", description = "Gets a saved view by ID")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = SavedViewResponse.class))),
        @ApiResponse(
            responseCode = "404",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
      })
  @GetMapping(path = "/{id}", produces = "application/json")
  public SavedViewResponse getView(@PathVariable("id") UUID id) {
    var userId = getCurrentUserId();
    log.info("Getting saved view {} for user {}", id, userId);

    var view = savedViewService.getView(id, userId);
    var transactionCount = savedViewService.countViewTransactions(view);
    return SavedViewResponse.from(view, transactionCount);
  }

  @PreAuthorize("hasAuthority('transactions:write')")
  @Operation(
      summary = "Update a saved view",
      description = "Updates a saved view's name or criteria")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = SavedViewResponse.class))),
        @ApiResponse(
            responseCode = "404",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
      })
  @PutMapping(path = "/{id}", consumes = "application/json", produces = "application/json")
  public SavedViewResponse updateView(
      @PathVariable("id") UUID id, @Valid @RequestBody UpdateSavedViewRequest request) {
    var userId = getCurrentUserId();
    log.info("Updating saved view {} for user {}", id, userId);

    var view = savedViewService.updateView(id, userId, request);
    var transactionCount = savedViewService.countViewTransactions(view);
    return SavedViewResponse.from(view, transactionCount);
  }

  @PreAuthorize("hasAuthority('transactions:delete')")
  @Operation(summary = "Delete a saved view", description = "Deletes a saved view")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "204"),
        @ApiResponse(
            responseCode = "404",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
      })
  @DeleteMapping(path = "/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteView(@PathVariable("id") UUID id) {
    var userId = getCurrentUserId();
    log.info("Deleting saved view {} for user {}", id, userId);

    savedViewService.deleteView(id, userId);
  }

  @PreAuthorize("hasAuthority('transactions:read')")
  @Operation(
      summary = "Get view transaction IDs",
      description =
          "Gets IDs of active transactions in this view, grouped by membership type "
              + "(matched, pinned, excluded). Soft-deleted transactions are excluded.")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ViewMembershipResponse.class))),
        @ApiResponse(
            responseCode = "404",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
      })
  @GetMapping(path = "/{id}/transactions", produces = "application/json")
  public ViewMembershipResponse getViewTransactions(@PathVariable("id") UUID id) {
    var userId = getCurrentUserId();
    log.info("Getting transaction IDs for view {} for user {}", id, userId);

    var membership = savedViewService.getViewTransactions(id, userId);
    return ViewMembershipResponse.from(membership);
  }

  @PreAuthorize("hasAuthority('transactions:write')")
  @Operation(summary = "Pin a transaction", description = "Pins a transaction to the view")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = SavedViewResponse.class))),
        @ApiResponse(
            responseCode = "404",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
      })
  @PostMapping(path = "/{id}/pin/{txnId}", produces = "application/json")
  public SavedViewResponse pinTransaction(
      @PathVariable("id") UUID id, @PathVariable("txnId") Long txnId) {
    var userId = getCurrentUserId();
    log.info("Pinning transaction {} to view {} for user {}", txnId, id, userId);

    var view = savedViewService.pinTransaction(id, userId, txnId);
    var transactionCount = savedViewService.countViewTransactions(view);
    return SavedViewResponse.from(view, transactionCount);
  }

  @PreAuthorize("hasAuthority('transactions:write')")
  @Operation(summary = "Unpin a transaction", description = "Removes a pin from the view")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = SavedViewResponse.class))),
        @ApiResponse(
            responseCode = "404",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
      })
  @DeleteMapping(path = "/{id}/pin/{txnId}", produces = "application/json")
  public SavedViewResponse unpinTransaction(
      @PathVariable("id") UUID id, @PathVariable("txnId") Long txnId) {
    var userId = getCurrentUserId();
    log.info("Unpinning transaction {} from view {} for user {}", txnId, id, userId);

    var view = savedViewService.unpinTransaction(id, userId, txnId);
    var transactionCount = savedViewService.countViewTransactions(view);
    return SavedViewResponse.from(view, transactionCount);
  }

  @PreAuthorize("hasAuthority('transactions:write')")
  @Operation(
      summary = "Exclude a transaction",
      description = "Excludes a transaction from the view")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = SavedViewResponse.class))),
        @ApiResponse(
            responseCode = "404",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
      })
  @PostMapping(path = "/{id}/exclude/{txnId}", produces = "application/json")
  public SavedViewResponse excludeTransaction(
      @PathVariable("id") UUID id, @PathVariable("txnId") Long txnId) {
    var userId = getCurrentUserId();
    log.info("Excluding transaction {} from view {} for user {}", txnId, id, userId);

    var view = savedViewService.excludeTransaction(id, userId, txnId);
    var transactionCount = savedViewService.countViewTransactions(view);
    return SavedViewResponse.from(view, transactionCount);
  }

  @PreAuthorize("hasAuthority('transactions:write')")
  @Operation(
      summary = "Remove exclusion",
      description =
          "Removes an exclusion, allowing the transaction to appear if it matches criteria")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = SavedViewResponse.class))),
        @ApiResponse(
            responseCode = "404",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
      })
  @DeleteMapping(path = "/{id}/exclude/{txnId}", produces = "application/json")
  public SavedViewResponse unexcludeTransaction(
      @PathVariable("id") UUID id, @PathVariable("txnId") Long txnId) {
    var userId = getCurrentUserId();
    log.info("Removing exclusion of transaction {} from view {} for user {}", txnId, id, userId);

    var view = savedViewService.unexcludeTransaction(id, userId, txnId);
    var transactionCount = savedViewService.countViewTransactions(view);
    return SavedViewResponse.from(view, transactionCount);
  }

  private String getCurrentUserId() {
    return SecurityContextUtil.getCurrentUserId()
        .orElseThrow(() -> new IllegalStateException("User ID not found in security context"));
  }
}
