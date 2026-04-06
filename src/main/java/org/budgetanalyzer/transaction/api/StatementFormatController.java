package org.budgetanalyzer.transaction.api;

import java.util.List;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.budgetanalyzer.service.api.ApiErrorResponse;
import org.budgetanalyzer.transaction.api.request.CreateStatementFormatRequest;
import org.budgetanalyzer.transaction.api.request.UpdateStatementFormatRequest;
import org.budgetanalyzer.transaction.api.response.StatementFormatResponse;
import org.budgetanalyzer.transaction.service.StatementFormatService;

/**
 * REST controller for managing statement format configurations.
 *
 * <p>Provides CRUD operations for statement formats used in file imports.
 */
@Tag(name = "Statement Formats", description = "Manage statement format configurations for imports")
@RestController
@RequestMapping(path = "/v1/statement-formats")
public class StatementFormatController {

  private static final Logger log = LoggerFactory.getLogger(StatementFormatController.class);

  private final StatementFormatService statementFormatService;

  public StatementFormatController(StatementFormatService statementFormatService) {
    this.statementFormatService = statementFormatService;
  }

  @PreAuthorize("hasAuthority('statementformats:read')")
  @Operation(
      summary = "List all statement formats",
      description = "Returns all configured statement formats (both enabled and disabled).")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    array =
                        @ArraySchema(
                            schema = @Schema(implementation = StatementFormatResponse.class))))
      })
  @GetMapping(produces = "application/json")
  public List<StatementFormatResponse> listFormats() {
    log.info("Received list statement formats request");

    return statementFormatService.getAllFormats().stream()
        .map(StatementFormatResponse::from)
        .toList();
  }

  @PreAuthorize("hasAuthority('statementformats:read')")
  @Operation(
      summary = "Get statement format details",
      description = "Returns details of a specific statement format by its format key.")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = StatementFormatResponse.class))),
        @ApiResponse(
            responseCode = "404",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples =
                        @ExampleObject(
                            name = "Format Not Found",
                            summary = "Statement format does not exist",
                            value =
                                """
                      {
                        "type": "NOT_FOUND",
                        "message": "Statement format not found with key: fake-format"
                      }
                      """)))
      })
  @GetMapping(path = "/{formatKey}", produces = "application/json")
  public StatementFormatResponse getFormat(
      @Parameter(description = "Unique format identifier", example = "capital-one")
          @PathVariable("formatKey")
          String formatKey) {
    log.info("Received get statement format request: {}", formatKey);

    return StatementFormatResponse.from(statementFormatService.getByFormatKey(formatKey));
  }

  @PreAuthorize("hasAuthority('statementformats:write')")
  @Operation(
      summary = "Create a new statement format",
      description =
          "Creates a new statement format configuration. For CSV formats, the column header "
              + "fields (dateHeader, descriptionHeader, creditHeader) are required.")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "201",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = StatementFormatResponse.class))),
        @ApiResponse(
            responseCode = "400",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)),
            description = "Validation error"),
        @ApiResponse(
            responseCode = "422",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples =
                        @ExampleObject(
                            name = "Duplicate Format Key",
                            summary = "Format key already exists",
                            value =
                                """
                      {
                        "type": "APPLICATION_ERROR",
                        "message": "Format key already exists: capital-one",
                        "code": "FORMAT_KEY_ALREADY_EXISTS"
                      }
                      """)))
      })
  @PostMapping(consumes = "application/json", produces = "application/json")
  public ResponseEntity<StatementFormatResponse> createFormat(
      @Valid @RequestBody CreateStatementFormatRequest request) {
    log.info(
        "Received create statement format request: {} ({})",
        request.formatKey(),
        request.formatType());

    var created = statementFormatService.createFormat(request);

    var location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{formatKey}")
            .buildAndExpand(created.getFormatKey())
            .toUri();

    return ResponseEntity.created(location).body(StatementFormatResponse.from(created));
  }

  @PreAuthorize("hasAuthority('statementformats:write')")
  @Operation(
      summary = "Update a statement format",
      description =
          "Updates an existing statement format. Only provided fields will be updated. "
              + "The format key and format type cannot be changed after creation.")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = StatementFormatResponse.class))),
        @ApiResponse(
            responseCode = "404",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)),
            description = "Statement format not found")
      })
  @PutMapping(path = "/{formatKey}", consumes = "application/json", produces = "application/json")
  public StatementFormatResponse updateFormat(
      @Parameter(description = "Unique format identifier", example = "capital-one")
          @PathVariable("formatKey")
          String formatKey,
      @Valid @RequestBody UpdateStatementFormatRequest request) {
    log.info("Received update statement format request: {}", formatKey);

    return StatementFormatResponse.from(statementFormatService.updateFormat(formatKey, request));
  }

  @PreAuthorize("hasAuthority('statementformats:delete')")
  @Operation(
      summary = "Disable a statement format",
      description =
          "Disables a statement format (soft delete). Disabled formats will not be available "
              + "for file imports but can be re-enabled by updating the format.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "204", description = "Format successfully disabled"),
        @ApiResponse(
            responseCode = "404",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)),
            description = "Statement format not found")
      })
  @DeleteMapping(path = "/{formatKey}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void disableFormat(
      @Parameter(description = "Unique format identifier", example = "capital-one")
          @PathVariable("formatKey")
          String formatKey) {
    log.info("Received disable statement format request: {}", formatKey);

    statementFormatService.disableFormat(formatKey);
  }
}
