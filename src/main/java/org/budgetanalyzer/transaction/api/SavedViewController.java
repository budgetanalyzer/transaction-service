package org.budgetanalyzer.transaction.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.budgetanalyzer.service.api.ApiErrorResponse;
import org.budgetanalyzer.service.security.SecurityContextUtil;
import org.budgetanalyzer.transaction.api.request.CloneSavedViewRequest;
import org.budgetanalyzer.transaction.api.request.CreateSavedViewRequest;
import org.budgetanalyzer.transaction.api.request.UpdateSavedViewRequest;
import org.budgetanalyzer.transaction.api.request.UpdateSavedViewTransactionsRequest;
import org.budgetanalyzer.transaction.api.response.SavedViewResponse;
import org.budgetanalyzer.transaction.api.response.ViewMembershipResponse;
import org.budgetanalyzer.transaction.service.SavedViewService;
import org.budgetanalyzer.transaction.service.dto.CloneSavedViewCommand;
import org.budgetanalyzer.transaction.service.dto.SavedViewCommand;
import org.budgetanalyzer.transaction.service.dto.SavedViewMembershipDelta;
import org.budgetanalyzer.transaction.service.dto.SavedViewPatch;

/** REST controller for user-owned static saved views. */
@Tag(name = "Saved Views", description = "Create and manage static transaction collections")
@RestController
@RequestMapping(path = "/v1/views")
public class SavedViewController {

  private final SavedViewService savedViewService;

  public SavedViewController(SavedViewService savedViewService) {
    this.savedViewService = savedViewService;
  }

  @PreAuthorize("hasAuthority('views:write')")
  @Operation(summary = "Create a static saved view")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        headers =
            @Header(
                name = "Location",
                description = "Canonical URL of the created saved view",
                schema = @Schema(type = "string", format = "uri")),
        content = @Content(schema = @Schema(implementation = SavedViewResponse.class))),
    @ApiResponse(
        responseCode = "400",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
    @ApiResponse(
        responseCode = "422",
        description =
            "SAVED_VIEW_MEMBERSHIP_STALE for unavailable membership or "
                + "SAVED_VIEW_MEMBERSHIP_LIMIT_EXCEEDED when the membership limit is exceeded or "
                + "SAVED_VIEW_NAME_ALREADY_EXISTS for a same-owner name conflict",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  })
  @PostMapping(consumes = "application/json", produces = "application/json")
  public ResponseEntity<SavedViewResponse> createView(
      @Valid @RequestBody CreateSavedViewRequest request) {
    var savedViewSummary =
        savedViewService.createView(
            currentUserId(), new SavedViewCommand(request.name(), request.transactionIds()));
    var location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(savedViewSummary.savedView().getId())
            .toUri();
    return ResponseEntity.created(location).body(SavedViewResponse.from(savedViewSummary));
  }

  @PreAuthorize("hasAuthority('views:write')")
  @Operation(
      summary = "Clone a static saved view",
      description = "Creates an independent copy of an owner-scoped saved view under a new name")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        headers =
            @Header(
                name = "Location",
                description = "Canonical URL of the cloned saved view",
                schema = @Schema(type = "string", format = "uri")),
        content = @Content(schema = @Schema(implementation = SavedViewResponse.class))),
    @ApiResponse(
        responseCode = "400",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "Source saved view not found for the authenticated owner",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
    @ApiResponse(
        responseCode = "422",
        description =
            "SAVED_VIEW_MEMBERSHIP_STALE when the source membership is unavailable or "
                + "SAVED_VIEW_NAME_ALREADY_EXISTS for a same-owner target name conflict",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  })
  @PostMapping(
      path = "/{sourceViewId}/clone",
      consumes = "application/json",
      produces = "application/json")
  public ResponseEntity<SavedViewResponse> cloneView(
      @PathVariable("sourceViewId") UUID sourceViewId,
      @Valid @RequestBody CloneSavedViewRequest request) {
    var savedViewSummary =
        savedViewService.cloneView(
            sourceViewId, currentUserId(), new CloneSavedViewCommand(request.name()));
    var location =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/v1/views/{id}")
            .buildAndExpand(savedViewSummary.savedView().getId())
            .toUri();
    return ResponseEntity.created(location).body(SavedViewResponse.from(savedViewSummary));
  }

  @PreAuthorize("hasAuthority('views:read')")
  @Operation(summary = "List static saved views")
  @ApiResponse(
      responseCode = "200",
      content =
          @Content(
              array = @ArraySchema(schema = @Schema(implementation = SavedViewResponse.class))))
  @GetMapping(produces = "application/json")
  public List<SavedViewResponse> listViews() {
    return savedViewService.getViewsForUser(currentUserId()).stream()
        .map(SavedViewResponse::from)
        .toList();
  }

  @PreAuthorize("hasAuthority('views:read')")
  @Operation(summary = "Get static saved-view metadata")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        content = @Content(schema = @Schema(implementation = SavedViewResponse.class))),
    @ApiResponse(
        responseCode = "404",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  })
  @GetMapping(path = "/{id}", produces = "application/json")
  public SavedViewResponse getView(@PathVariable("id") UUID id) {
    return SavedViewResponse.from(savedViewService.getView(id, currentUserId()));
  }

  @PreAuthorize("hasAuthority('views:write')")
  @Operation(summary = "Rename a static saved view")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        content = @Content(schema = @Schema(implementation = SavedViewResponse.class))),
    @ApiResponse(
        responseCode = "400",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
    @ApiResponse(
        responseCode = "404",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
    @ApiResponse(
        responseCode = "422",
        description = "SAVED_VIEW_NAME_ALREADY_EXISTS for a same-owner name conflict",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  })
  @PatchMapping(path = "/{id}", consumes = "application/json", produces = "application/json")
  public SavedViewResponse updateView(
      @PathVariable("id") UUID id, @Valid @RequestBody UpdateSavedViewRequest request) {
    return SavedViewResponse.from(
        savedViewService.updateView(id, currentUserId(), new SavedViewPatch(request.name())));
  }

  @PreAuthorize("hasAuthority('views:delete')")
  @Operation(summary = "Delete a static saved view")
  @ApiResponses({
    @ApiResponse(responseCode = "204"),
    @ApiResponse(
        responseCode = "404",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  })
  @DeleteMapping(path = "/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteView(@PathVariable("id") UUID id) {
    savedViewService.deleteView(id, currentUserId());
  }

  @PreAuthorize("hasAuthority('views:read')")
  @Operation(summary = "Get complete static saved-view membership")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        content = @Content(schema = @Schema(implementation = ViewMembershipResponse.class))),
    @ApiResponse(
        responseCode = "404",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  })
  @GetMapping(path = "/{id}/transactions", produces = "application/json")
  public ViewMembershipResponse getViewTransactions(@PathVariable("id") UUID id) {
    return new ViewMembershipResponse(savedViewService.getViewTransactions(id, currentUserId()));
  }

  @PreAuthorize("hasAuthority('views:write')")
  @Operation(summary = "Apply a static saved-view membership delta")
  @ApiResponses({
    @ApiResponse(responseCode = "204"),
    @ApiResponse(
        responseCode = "400",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
    @ApiResponse(
        responseCode = "404",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
    @ApiResponse(
        responseCode = "422",
        description =
            "SAVED_VIEW_MEMBERSHIP_STALE for unavailable additions or "
                + "SAVED_VIEW_MEMBERSHIP_LIMIT_EXCEEDED when the membership limit is exceeded",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class)))
  })
  @PatchMapping(path = "/{id}/transactions", consumes = "application/json")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void updateViewTransactions(
      @PathVariable("id") UUID id, @Valid @RequestBody UpdateSavedViewTransactionsRequest request) {
    savedViewService.updateViewTransactions(
        id,
        currentUserId(),
        new SavedViewMembershipDelta(request.addTransactionIds(), request.removeTransactionIds()));
  }

  private String currentUserId() {
    return SecurityContextUtil.getCurrentUserId()
        .orElseThrow(() -> new IllegalStateException("User ID not found in security context"));
  }
}
