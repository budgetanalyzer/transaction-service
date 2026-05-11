# Opaque Preview Token and Filename Validation Fixes

## Implementation Status

Token encryption and configuration are implemented as of 2026-05-11. New
preview tokens use the `v2.<base64url(iv)>.<base64url(ciphertext)>` AES-GCM
format described below, and legacy `v1` signed-cleartext tokens are rejected.
The service now uses the `encryption-secret` configuration property. The value
is normalized with SHA-256 and used as AES-GCM key material. Filename validation
remains tracked by the separate section below.

## Context

The preview-to-batch import flow returns `previewImportToken` from
`POST /v1/transactions/preview` and requires that token on
`POST /v1/transactions/batch`. The token carries source-file metadata used to
record successful `file_import` rows after batch import.

Two follow-up bugs need to be fixed:

- The token is documented as opaque and the API says it does not expose the raw
  `contentHash`. This was fixed by replacing Base64URL-encoded JSON plus HMAC
  with encrypted `v2` AES-GCM tokens.
- Multipart uploads can omit the `filename` parameter. Preview can currently
  issue a token with a null or blank original filename, but batch verification
  later rejects that token because `originalFilename` is required.

## Goals

- Make `previewImportToken` truly opaque to clients.
- Preserve integrity checks, owner checks, TTL expiration, and source-file
  metadata recovery during batch import.
- Stop issuing preview tokens that `/batch` will later reject because of a
  missing filename.
- Keep the preview and batch API shape unchanged for clients.
- Add regression tests that prove the token payload is not client-decodable and
  that missing filenames fail during preview.
- Update API and import documentation in the same change.

## Non-Goals

- Do not expose `contentHash` as a public API field.
- Do not add a new client-visible file identifier.
- Do not redesign duplicate transaction detection.
- Do not require the client to reupload the original source file during batch
  import.
- Do not add server-side preview persistence unless encrypted stateless tokens
  are rejected during implementation review.

## Decisions

- Use a versioned encrypted token format for new tokens:
  `v2.<base64url(iv)>.<base64url(ciphertext)>`.
- Use authenticated encryption so confidentiality and integrity are enforced by
  the same primitive. AES-GCM is the preferred Java standard-library option.
- Keep `PreviewImportToken` as the internal DTO returned by
  `PreviewImportTokenService.verifyToken(...)`.
- Keep the token stateless. The encrypted payload still contains the existing
  metadata, but clients cannot inspect or modify it.
- Derive or load a fixed-length encryption key from service configuration. The
  implementation should not use the UTF-8 encryption secret bytes directly as
  an AES key unless they are explicitly normalized to a supported key length.
- Reject null or blank original filenames during preview before token creation.
  Do not substitute a synthetic filename unless product requirements explicitly
  allow imports without uploaded filenames.
- Treat in-flight `v1` tokens as invalid after the deployment unless a rollout
  requirement says otherwise. Preview tokens are short-lived, and rejecting old
  cleartext tokens closes the leak completely.

## Token Implementation Plan

1. Replace the current signed-cleartext encoder in `PreviewImportTokenService`
   with encrypted `v2` token creation.
2. Serialize `PreviewImportToken` to JSON exactly as today.
3. Generate a fresh 96-bit IV for each token with `SecureRandom`.
4. Encrypt the JSON payload with `AES/GCM/NoPadding`.
5. Bind the token version as additional authenticated data so a ciphertext
   cannot be moved between token versions.
6. Return `v2.<iv>.<ciphertext>` using unpadded Base64URL encoding for both
   binary segments.
7. On verification, require exactly three token segments and version `v2`.
8. Decode IV and ciphertext with Base64URL. Reject malformed input as
   `PREVIEW_IMPORT_TOKEN_INVALID`.
9. Decrypt with the configured key and authenticated data. Reject authentication
   failures as `PREVIEW_IMPORT_TOKEN_INVALID`.
10. Deserialize the decrypted JSON into `PreviewImportToken`.
11. Keep the existing required-field, owner, and expiration validation after
    decryption.
12. Remove HMAC-specific implementation details if AES-GCM fully replaces them.

## Configuration Plan

Implemented as of 2026-05-11 with the preferred property name:
`budgetanalyzer.transaction.preview-import-token.encryption-secret`, sourced by
default from `PREVIEW_IMPORT_TOKEN_ENCRYPTION_SECRET`. The configured value must
be non-blank text and is normalized with SHA-256 before constructing the
AES-GCM key.

Preferred configuration shape:

```yaml
budgetanalyzer:
  transaction:
    preview-import-token:
      encryption-secret: ${PREVIEW_IMPORT_TOKEN_ENCRYPTION_SECRET}
      ttl: 30m
```

Implemented option:

- Added `encryptionSecret` to `PreviewImportTokenProperties` and removed the
  previous `signingSecret` property name.

Validation requirements:

- The secret must be configured and non-blank.
- The AES key used by the cipher must be deterministic across restarts.
- The service accepts an arbitrary non-blank text secret and normalizes it with
  SHA-256 before constructing `SecretKeySpec`.
- The README documents the exact required format so deployments do not
  accidentally rotate or mis-size the key.

## Filename Validation Plan

1. Add a small helper in `TransactionImportService.previewWithExtractor(...)`
   or a private method called by it:
   `requireOriginalFilename(MultipartFile file)`.
2. Trim `file.getOriginalFilename()`.
3. If the trimmed value is null or blank, throw a `BusinessException` before
   reading file bytes or creating a preview token.
4. Use the validated filename for logging, `createToken(...)`, and
   `PreviewResult.sourceFile`.
5. Keep `PreviewImportTokenService.validateRequiredFields(...)` unchanged so
   batch verification still rejects malformed or legacy tokens defensively.

Recommended error behavior:

- Use `CSV_PARSING_ERROR` only if the service currently treats all preview
  upload problems as parsing failures.
- Prefer adding a more precise `FILE_UPLOAD_INVALID` or
  `MISSING_ORIGINAL_FILENAME` error code if that aligns with the service-common
  error handling conventions.
- Update controller examples if a new error code is added.

## Tests

Add or update unit tests:

- `PreviewImportTokenServiceTest#createAndVerifyToken_validToken_returnsPayload`
  still verifies round trip behavior.
- Add a token opacity test that creates a token and asserts that splitting the
  token and Base64URL-decoding any payload segment does not reveal
  `contentHash`, `ownerId`, `originalFilename`, or valid JSON.
- Update tampering tests to mutate the IV or ciphertext and assert
  `PREVIEW_IMPORT_TOKEN_INVALID`.
- Update malformed token tests to cover wrong segment count, wrong version,
  invalid Base64URL, and decrypt failures.
- Keep owner mismatch and expiration tests.
- Remove test helpers that construct hand-signed `v1` tokens unless temporary
  backward compatibility is intentionally supported.
- Add `TransactionImportServiceTest` coverage for null and blank original
  filenames. Verify that `previewImportTokenService.createToken(...)`,
  `fileImportTrackingService.checkFile(...)`, and extractor parsing are not
  called after filename rejection.
- Add a positive filename test that verifies whitespace is trimmed before token
  creation and response mapping if trimming is implemented.

Run:

```bash
./gradlew clean spotlessApply
./gradlew clean build
```

## Documentation Updates

Update the affected docs in the same implementation change:

- `docs/api/README.md`
  - Describe `previewImportToken` as encrypted, time-limited, and opaque.
  - Keep saying the raw `contentHash` is not exposed.
  - Document missing original filename behavior if a specific API error is
    introduced.
- `docs/csv-import.md`
  - Keep client guidance to treat `previewImportToken` as opaque client state.
  - Mention that uploads must include a filename if the service rejects missing
    filenames.
- `docs/plans/preview-warning-removal-and-file-reupload-warning.md`
  - Replace wording that says "opaque signed token" with "opaque encrypted
    token" or cross-reference this corrective plan.
- `README.md`
  - Update only if local setup/configuration changes, such as a renamed preview
    token secret environment variable.

## Rollout Notes

- Because `v1` tokens are short-lived preview artifacts, it is acceptable for
  deployment to invalidate tokens issued before the change. Users can rerun
  preview.
- If uninterrupted in-flight imports are required, add temporary `v1`
  verification support for one TTL window, but never issue new `v1` tokens.
- Rotating the encryption secret invalidates all outstanding preview tokens.
  That is acceptable operationally but should be documented near the
  configuration.

## Acceptance Criteria

- A preview token returned by `/preview` cannot be decoded by a client to reveal
  `contentHash` or any other JSON payload fields.
- `/batch` accepts valid encrypted preview tokens for the authenticated owner
  and still records `file_import` metadata correctly.
- `/batch` rejects tampered, malformed, expired, or wrong-owner tokens.
- `/preview` rejects uploads with null, blank, or whitespace-only original
  filenames before issuing a token.
- Unit and controller tests pass.
- API and CSV import docs match the implemented behavior.
