Plan: Add Bulk Delete Endpoint                                                  │
     │                                                                                 │
     │ Implementation Steps                                                            │
     │                                                                                 │
     │ 1. Create Request DTO                                                           │
     │ - File: src/main/java/org/budgetanalyzer/transaction/api/BulkDeleteRequest.java │
     │ - Java record with List<Long> ids                                               │
     │ - Validation: @NotEmpty, @Size(max = 1000)                                      │
     │ - OpenAPI @Schema annotations                                                   │
     │                                                                                 │
     │ 2. Add Service Method                                                           │
     │ - File:                                                                         │
     │ src/main/java/org/budgetanalyzer/transaction/service/TransactionService.java    │
     │ - Method: deleteTransactions(List<Long> ids, String deletedBy)                  │
     │ - @Transactional for atomic operation                                           │
     │ - Fetch all transactions using findByIdActive() for each ID                     │
     │ - Throw ResourceNotFoundException if any ID not found                           │
     │ - Call markDeleted(deletedBy) on each transaction                               │
     │ - Batch save with saveAll()                                                     │
     │                                                                                 │
     │ 3. Add Controller Endpoint                                                      │
     │ - File:                                                                         │
     │ src/main/java/org/budgetanalyzer/transaction/api/TransactionController.java     │
     │ - Endpoint: DELETE /v1/transactions/bulk                                        │
     │ - @ResponseStatus(HttpStatus.NO_CONTENT)                                        │
     │ - @PreAuthorize("isAuthenticated()")                                            │
     │ - OpenAPI annotations (@Operation, @ApiResponses)                               │
     │ - Accept @Valid @RequestBody BulkDeleteRequest                                  │
     │                                                                                 │
     │ 4. Add Unit Tests                                                               │
     │ - Test service method with valid IDs                                            │
     │ - Test service throws exception when ID not found                               │
     │ - Test controller endpoint integration                                          │
     │                                                                                 │
     │ 5. Format and Build                                                             │
     │ - Run ./gradlew clean spotlessApply build                                       │
     │                                                                                 │
     │ Behavior                                                                        │
     │                                                                                 │
     │ - Atomic: If any transaction ID is not found or already deleted, the entire     │
     │ request fails with 404                                                          │
     │ - Limit: Maximum 1000 IDs per request                                           │
     │ - Soft delete: Uses existing markDeleted() pattern                              │
A
A
A
A
A
A
     ╰────────────────────────────────────────────────────────
