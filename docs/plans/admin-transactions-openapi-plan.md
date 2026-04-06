# Admin Transactions OpenAPI Plan

## Goal

Fix the admin transactions OpenAPI contract so the frontend can consume a complete, typed
`GET /v1/admin/transactions` response and unblock the backend side of the Phase 3 admin
transactions view.

## Non-Goals

- `transaction-service` will not expose denormalized owner identity fields such as `ownerEmail` or
  `ownerName`.
- `transaction-service` will not implement user-directory or user-lookup APIs for admin UX.
- Supporting a human-readable owner picker or owner directory is out of scope for this service and
  not intended to be implemented here.

## Current State

The transaction service implementation is ahead of the unified OpenAPI YAML.

Already implemented in `transaction-service`:

- `GET /v1/admin/transactions` returns `PagedResponse<AdminTransactionResponse>`.
- `AdminTransactionResponse` already includes `ownerId`.
- Controller tests already assert the stable `content` + `metadata` contract and `ownerId` on
  each item.
- Repository docs already describe the admin paged response contract.

Missing or incorrect in the unified spec at
`/workspace/budget-analyzer-web/docs/api/budget-analyzer-api.yaml`:

1. `/v1/admin/transactions` has an empty `200` response schema.
2. The endpoint is documented with a synthetic required `filter` query parameter instead of the
   exploded query fields.
3. `AdminTransactionResponse` is absent from components.
4. `PagedResponse` and `PageMetadataResponse` are absent from components.
5. The merged spec only exposes the base `TransactionResponse`, which does not include `ownerId`.

## Findings

### 1. The implementation already returns the right admin response type

`src/main/java/org/budgetanalyzer/transaction/api/AdminTransactionController.java`

- `searchTransactions(...)` returns `PagedResponse<AdminTransactionResponse>`.
- The actual JSON contract is already correct at runtime.

`src/main/java/org/budgetanalyzer/transaction/api/response/AdminTransactionResponse.java`

- The admin-specific DTO already exposes `ownerId`.

`src/test/java/org/budgetanalyzer/transaction/api/AdminTransactionControllerTest.java`

- Tests already assert:
  - `$.content[0].ownerId`
  - `$.metadata.page`
  - `$.metadata.totalElements`
  - `$.metadata.totalPages`

### 2. The OpenAPI annotations are too weak for Springdoc to emit the full schema

`src/main/java/org/budgetanalyzer/transaction/api/AdminTransactionController.java`

- The `200` response is declared as:

```java
@ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json"))
```

- That explicitly produces an empty `application/json` schema in the generated spec.

- `TransactionFilter filter` is not annotated with `@ParameterObject`, so Springdoc documents it
  as a single `filter` query parameter instead of expanding `ownerId`, `bankName`, `dateFrom`,
  `minAmount`, and the other fields.

### 3. The merged spec is missing the wrapper schemas entirely

`service-common` already defines:

- `org.budgetanalyzer.service.api.PagedResponse`
- `org.budgetanalyzer.service.api.PageMetadataResponse`

Those types are not present in the unified YAML today, which means the frontend cannot infer the
response envelope from the merged OpenAPI document.

### 4. Human-readable owner identity is a separate concern from this plan

The frontend plan in
`/workspace/budget-analyzer-web/docs/plans/admin-ux-separate-identity.md` requires:

- user info per transaction for the owner column
- user-based filtering UX

The current admin transaction response only includes `ownerId`. That is sufficient for a raw ID
column and exact-ID filtering, which is the boundary this service intends to support.

Human-readable owner identity such as email or display name is explicitly out of scope for
`transaction-service`.

## Decisions

### Decision 1: Do not add admin-only fields to `TransactionResponse`

Do not add `ownerId`, `ownerEmail`, or `ownerName` to the shared user-scoped
`TransactionResponse`.

Reason:

- user-scoped transaction endpoints should keep their existing contract
- admin response shape is already separated in code
- the problem is missing OpenAPI emission, not missing runtime data for the admin route

### Decision 2: Keep the admin response admin-specific

The admin route should continue to use `AdminTransactionResponse`, or a concrete admin-specific
paged wrapper if Springdoc needs help materializing the generic schema.

### Decision 3: Human-readable owner identity is not intended in transaction-service

This service stops at `ownerId`.

If some future admin UX needs user-directory behavior, that must be solved outside
`transaction-service`. It is not part of this plan and not intended for implementation here.

## Work Plan

### Phase 1: Fix transaction-service OpenAPI generation

Scope: `transaction-service`

1. Add `@ParameterObject` to `TransactionFilter filter` on:
   - `GET /v1/admin/transactions`
   - `GET /v1/admin/transactions/count`
2. Replace the empty `200` response annotation on admin search with an explicit schema.
3. If Springdoc still does not emit the generic wrapper correctly, introduce a concrete OpenAPI
   wrapper such as `AdminTransactionSearchResponse` that exposes:
   - `content: AdminTransactionResponse[]`
   - `metadata: PageMetadataResponse`
4. Confirm the generated `/v3/api-docs` for transaction-service includes:
   - `AdminTransactionResponse`
   - pagination metadata
   - exploded filter query parameters
   - non-empty `200` schema for `/v1/admin/transactions`

### Phase 2: Add OpenAPI regression coverage

Scope: `transaction-service`

Add a test that verifies the generated OpenAPI document includes the admin search contract.

Minimum assertions:

- `/v1/admin/transactions` `200` response has a schema
- item schema includes `ownerId`
- pagination metadata includes `page`, `size`, `numberOfElements`, `totalElements`, `totalPages`,
  `first`, `last`
- filter params are exposed individually, including `ownerId`

Without this, the service can regress while controller tests still pass.

### Phase 3: Regenerate and validate the unified YAML

Scope: web aggregation step

After the transaction-service fix:

1. regenerate the service OpenAPI output
2. regenerate `/workspace/budget-analyzer-web/docs/api/budget-analyzer-api.yaml`
3. confirm the unified YAML now contains:
   - `/v1/admin/transactions` with a real `200` schema
   - `AdminTransactionResponse`
   - paged wrapper and metadata schemas
   - exploded admin filter params

If service-local `/v3/api-docs` is correct but the unified YAML is still wrong, the remaining bug is
in the web-side aggregation process, not in Springdoc annotations.

### Out Of Scope: Human-readable owner lookup

Scope: not part of this service plan

This plan does not include adding `ownerEmail`, `ownerName`, or any user lookup/list API from
`transaction-service`.

If the frontend later needs a human-readable owner directory or picker, that must be handled as a
separate cross-service design discussion. It is not required to complete the OpenAPI fix described
here.

## Acceptance Criteria

### Transaction-service acceptance

- `GET /v1/admin/transactions` has a concrete `200` schema in generated OpenAPI
- the response item schema includes `ownerId`
- pagination metadata is documented
- admin filter fields are documented as real query params, not a single `filter` param
- the unified YAML reflects the same contract

## Open Questions

1. Is raw `ownerId` sufficient for the initial admin transactions UI, or does the frontend need a
   separate owner-resolution source outside this service?

## Recommended Execution Order

1. Fix transaction-service OpenAPI generation.
2. Add the OpenAPI regression test.
3. Regenerate and verify the unified YAML.
4. Start frontend Phase 3 once the documented `ownerId`-based contract is present.
