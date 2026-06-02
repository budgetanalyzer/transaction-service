package org.budgetanalyzer.transaction.api;

import java.util.List;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
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
import org.budgetanalyzer.service.security.SecurityContextUtil;
import org.budgetanalyzer.transaction.api.request.CreateStatementFormatRequest;
import org.budgetanalyzer.transaction.api.request.CsvWizardMappingPreviewRequest;
import org.budgetanalyzer.transaction.api.request.CsvWizardSaveRequest;
import org.budgetanalyzer.transaction.api.request.PdfWizardMappingPreviewRequest;
import org.budgetanalyzer.transaction.api.request.PdfWizardSaveRequest;
import org.budgetanalyzer.transaction.api.request.UpdateStatementFormatRequest;
import org.budgetanalyzer.transaction.api.response.CsvWizardAnalysisResponse;
import org.budgetanalyzer.transaction.api.response.CsvWizardPreviewResponse;
import org.budgetanalyzer.transaction.api.response.PdfWizardAnalysisResponse;
import org.budgetanalyzer.transaction.api.response.PdfWizardPreviewResponse;
import org.budgetanalyzer.transaction.api.response.StatementFormatResponse;
import org.budgetanalyzer.transaction.service.CsvStatementFormatWizardService;
import org.budgetanalyzer.transaction.service.PdfStatementFormatWizardService;
import org.budgetanalyzer.transaction.service.StatementFormatService;
import org.budgetanalyzer.transaction.service.dto.StatementFormatCommand;
import org.budgetanalyzer.transaction.service.dto.StatementFormatPatch;

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
  private final CsvStatementFormatWizardService csvStatementFormatWizardService;
  private final PdfStatementFormatWizardService pdfStatementFormatWizardService;

  public StatementFormatController(
      StatementFormatService statementFormatService,
      CsvStatementFormatWizardService csvStatementFormatWizardService,
      PdfStatementFormatWizardService pdfStatementFormatWizardService) {
    this.statementFormatService = statementFormatService;
    this.csvStatementFormatWizardService = csvStatementFormatWizardService;
    this.pdfStatementFormatWizardService = pdfStatementFormatWizardService;
  }

  @PreAuthorize("hasAnyAuthority('statementformats:read', 'statementformats:read:any')")
  @Operation(
      summary = "List all statement formats",
      description =
          "Returns statement formats visible to the caller. Formats hidden by the current user are "
              + "excluded by default; pass includeHidden=true for management screens.")
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
  public List<StatementFormatResponse> listFormats(
      @Parameter(description = "Include formats hidden by the current user", example = "true")
          @RequestParam(name = "includeHidden", defaultValue = "false")
          boolean includeHidden) {
    log.info("Received list statement formats request");

    var userId = getCurrentUserId();
    var canReadAny = SecurityContextUtil.hasAuthority("statementformats:read:any");

    return statementFormatService.listFormats(userId, canReadAny, includeHidden).stream()
        .map(StatementFormatResponse::from)
        .toList();
  }

  @PreAuthorize("hasAnyAuthority('statementformats:read', 'statementformats:read:any')")
  @Operation(
      summary = "Get statement format details",
      description = "Returns details of a specific statement format by ID.")
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
                        "message": "Statement format not found with id: 999"
                      }
                      """)))
      })
  @GetMapping(path = "/{id}", produces = "application/json")
  public StatementFormatResponse getFormat(
      @Parameter(description = "Statement format ID", example = "123") @PathVariable("id")
          Long id) {
    log.info("Received get statement format request: {}", id);

    var userId = getCurrentUserId();
    var canReadAny = SecurityContextUtil.hasAuthority("statementformats:read:any");
    return StatementFormatResponse.from(statementFormatService.getById(id, userId, canReadAny));
  }

  @PreAuthorize("hasAnyAuthority('statementformats:write', 'statementformats:write:any')")
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
            description = "Business rule violation",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
      })
  @PostMapping(consumes = "application/json", produces = "application/json")
  public ResponseEntity<StatementFormatResponse> createFormat(
      @Valid @RequestBody CreateStatementFormatRequest request) {
    log.info(
        "Received create statement format request: {} ({})",
        request.displayName(),
        request.formatType());

    var userId = getCurrentUserId();
    var canWriteAny = SecurityContextUtil.hasAuthority("statementformats:write:any");
    var command =
        new StatementFormatCommand(
            request.displayName(),
            request.formatType(),
            request.bankName(),
            request.defaultCurrencyIsoCode(),
            request.scope(),
            request.dateHeader(),
            request.dateFormat(),
            request.descriptionHeader(),
            request.creditHeader(),
            request.debitHeader(),
            request.typeHeader(),
            request.categoryHeader());
    var created = statementFormatService.createFormat(command, userId, canWriteAny);

    var location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(created.getId())
            .toUri();

    return ResponseEntity.created(location).body(StatementFormatResponse.from(created));
  }

  @PreAuthorize("hasAnyAuthority('statementformats:write', 'statementformats:write:any')")
  @Operation(
      summary = "Analyze a CSV sample for statement format creation",
      description =
          "Parses a multipart CSV sample, returns headers and sample rows, and infers an initial "
              + "CSV column mapping without persisting the uploaded file or creating import state.")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = CsvWizardAnalysisResponse.class))),
        @ApiResponse(
            responseCode = "422",
            description = "CSV parsing or analysis error",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
      })
  @PostMapping(
      path = "/csv-wizard/analyze",
      consumes = "multipart/form-data",
      produces = "application/json")
  public CsvWizardAnalysisResponse analyzeCsvSample(@RequestPart("file") MultipartFile file)
      throws java.io.IOException {
    log.info("Received CSV statement format wizard analysis request");

    return CsvWizardAnalysisResponse.from(
        csvStatementFormatWizardService.analyze(file.getBytes(), file.getOriginalFilename()));
  }

  @PreAuthorize("hasAnyAuthority('statementformats:write', 'statementformats:write:any')")
  @Operation(
      summary = "Preview a CSV wizard mapping",
      description =
          "Parses read-only transaction preview rows from a multipart CSV sample and confirmed "
              + "mapping. This does not create a statement format, preview token, file import, or "
              + "transactions.")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = CsvWizardPreviewResponse.class))),
        @ApiResponse(
            responseCode = "422",
            description = "Mapping validation error",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
      })
  @PostMapping(
      path = "/csv-wizard/preview",
      consumes = "multipart/form-data",
      produces = "application/json")
  public CsvWizardPreviewResponse previewCsvMapping(
      @RequestPart("file") MultipartFile file,
      @Valid @RequestPart("request") CsvWizardMappingPreviewRequest request)
      throws java.io.IOException {
    log.info("Received CSV statement format wizard mapping preview request");

    return CsvWizardPreviewResponse.from(
        csvStatementFormatWizardService.preview(
            file.getBytes(), file.getOriginalFilename(), request.toServiceDto()));
  }

  @PreAuthorize("hasAnyAuthority('statementformats:write', 'statementformats:write:any')")
  @Operation(
      summary = "Save a CSV wizard statement format",
      description =
          "Validates the confirmed mapping against the sample CSV and creates a user-scoped CSV "
              + "statement format with one enabled CSV_COLUMN_CONFIG parser revision.")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "201",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = StatementFormatResponse.class))),
        @ApiResponse(
            responseCode = "422",
            description = "Mapping validation error",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
      })
  @PostMapping(
      path = "/csv-wizard/save",
      consumes = "multipart/form-data",
      produces = "application/json")
  public ResponseEntity<StatementFormatResponse> saveCsvWizardFormat(
      @RequestPart("file") MultipartFile file,
      @Valid @RequestPart("request") CsvWizardSaveRequest request)
      throws java.io.IOException {
    log.info("Received CSV statement format wizard save request: {}", request.displayName());

    var userId = getCurrentUserId();
    var canWriteAny = SecurityContextUtil.hasAuthority("statementformats:write:any");
    var created =
        csvStatementFormatWizardService.save(
            file.getBytes(),
            file.getOriginalFilename(),
            request.toServiceDto(),
            userId,
            canWriteAny);
    var location =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/v1/statement-formats/{id}")
            .buildAndExpand(created.getId())
            .toUri();

    return ResponseEntity.created(location).body(StatementFormatResponse.from(created));
  }

  @PreAuthorize("hasAnyAuthority('statementformats:write', 'statementformats:write:any')")
  @Operation(
      summary = "Analyze a PDF sample for statement format creation",
      description =
          "Extracts text from a multipart PDF sample and returns ranked transaction-table "
              + "candidates with inferred mappings, confidence, sample rows, and unsupported-file "
              + "rejection reasons. This does not persist the uploaded file or create import "
              + "state.")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = PdfWizardAnalysisResponse.class))),
        @ApiResponse(
            responseCode = "422",
            description = "PDF parsing or analysis error",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
      })
  @PostMapping(
      path = "/pdf-wizard/analyze",
      consumes = "multipart/form-data",
      produces = "application/json")
  public PdfWizardAnalysisResponse analyzePdfSample(@RequestPart("file") MultipartFile file)
      throws java.io.IOException {
    log.info("Received PDF statement format wizard analysis request");

    return PdfWizardAnalysisResponse.from(
        pdfStatementFormatWizardService.analyze(file.getBytes(), file.getOriginalFilename()));
  }

  @PreAuthorize("hasAnyAuthority('statementformats:write', 'statementformats:write:any')")
  @Operation(
      summary = "Preview a PDF wizard mapping",
      description =
          "Parses read-only transaction preview rows from a multipart text-PDF sample and "
              + "confirmed table mapping. This does not create a statement format, preview token, "
              + "file import, or transactions.")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = PdfWizardPreviewResponse.class))),
        @ApiResponse(
            responseCode = "422",
            description = "Mapping validation error",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
      })
  @PostMapping(
      path = "/pdf-wizard/preview",
      consumes = "multipart/form-data",
      produces = "application/json")
  public PdfWizardPreviewResponse previewPdfMapping(
      @RequestPart("file") MultipartFile file,
      @Valid @RequestPart("request") PdfWizardMappingPreviewRequest request)
      throws java.io.IOException {
    log.info("Received PDF statement format wizard mapping preview request");

    return PdfWizardPreviewResponse.from(
        pdfStatementFormatWizardService.preview(
            file.getBytes(), file.getOriginalFilename(), request.toServiceDto()));
  }

  @PreAuthorize("hasAnyAuthority('statementformats:write', 'statementformats:write:any')")
  @Operation(
      summary = "Save a PDF wizard statement format",
      description =
          "Validates the confirmed text-PDF table mapping against the sample PDF and creates a "
              + "user-scoped PDF statement format with one enabled PDF_TEXT_TABLE_CONFIG parser "
              + "revision.")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "201",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = StatementFormatResponse.class))),
        @ApiResponse(
            responseCode = "422",
            description = "Mapping validation error",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
      })
  @PostMapping(
      path = "/pdf-wizard/save",
      consumes = "multipart/form-data",
      produces = "application/json")
  public ResponseEntity<StatementFormatResponse> savePdfWizardFormat(
      @RequestPart("file") MultipartFile file,
      @Valid @RequestPart("request") PdfWizardSaveRequest request)
      throws java.io.IOException {
    log.info("Received PDF statement format wizard save request: {}", request.displayName());

    var userId = getCurrentUserId();
    var created =
        pdfStatementFormatWizardService.save(
            file.getBytes(), file.getOriginalFilename(), request.toServiceDto(), userId);
    var location =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/v1/statement-formats/{id}")
            .buildAndExpand(created.getId())
            .toUri();

    return ResponseEntity.created(location).body(StatementFormatResponse.from(created));
  }

  @PreAuthorize("hasAnyAuthority('statementformats:write', 'statementformats:write:any')")
  @Operation(
      summary = "Update a statement format",
      description =
          "Updates an existing statement format. Only provided fields will be updated. "
              + "The ID, scope, owner, and format type cannot be changed after creation.")
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
  @PutMapping(path = "/{id}", consumes = "application/json", produces = "application/json")
  public StatementFormatResponse updateFormat(
      @Parameter(description = "Statement format ID", example = "123") @PathVariable("id") Long id,
      @Valid @RequestBody UpdateStatementFormatRequest request) {
    log.info("Received update statement format request: {}", id);

    var userId = getCurrentUserId();
    var canWriteAny = SecurityContextUtil.hasAuthority("statementformats:write:any");
    var patch =
        new StatementFormatPatch(
            request.displayName(),
            request.bankName(),
            request.defaultCurrencyIsoCode(),
            request.enabled());
    return StatementFormatResponse.from(
        statementFormatService.updateFormat(id, patch, userId, canWriteAny));
  }

  @PreAuthorize("hasAnyAuthority('statementformats:write', 'statementformats:write:any')")
  @Operation(
      summary = "Hide a statement format",
      description =
          "Hides a statement format from the current user's normal import selection lists. The "
              + "operation is idempotent and does not disable the format for other users.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "204", description = "Statement format hidden"),
        @ApiResponse(
            responseCode = "404",
            description = "Statement format not found or not visible to the caller",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
      })
  @PostMapping(path = "/{id}/hide")
  public ResponseEntity<Void> hideFormat(
      @Parameter(description = "Statement format ID", example = "123") @PathVariable("id")
          Long id) {
    log.info("Received hide statement format request: {}", id);

    statementFormatService.hideFormat(id, getCurrentUserId());
    return ResponseEntity.noContent().build();
  }

  @PreAuthorize("hasAnyAuthority('statementformats:write', 'statementformats:write:any')")
  @Operation(
      summary = "Unhide a statement format",
      description =
          "Restores a statement format to the current user's normal import selection lists. The "
              + "operation is idempotent.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "204", description = "Statement format unhidden"),
        @ApiResponse(
            responseCode = "404",
            description = "Statement format not found or not visible to the caller",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
      })
  @PostMapping(path = "/{id}/unhide")
  public ResponseEntity<Void> unhideFormat(
      @Parameter(description = "Statement format ID", example = "123") @PathVariable("id")
          Long id) {
    log.info("Received unhide statement format request: {}", id);

    statementFormatService.unhideFormat(id, getCurrentUserId());
    return ResponseEntity.noContent().build();
  }

  private String getCurrentUserId() {
    return SecurityContextUtil.getCurrentUserId()
        .orElseThrow(() -> new IllegalStateException("User ID not found in security context"));
  }
}
