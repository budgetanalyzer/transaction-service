package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.budgetanalyzer.transaction.util.TestConstants.ACCOUNT_ID;
import static org.budgetanalyzer.transaction.util.TestConstants.CONTENT_HASH;
import static org.budgetanalyzer.transaction.util.TestConstants.FILE_SIZE_BYTES;
import static org.budgetanalyzer.transaction.util.TestConstants.ORIGINAL_FILENAME;
import static org.budgetanalyzer.transaction.util.TestConstants.OTHER_USER_ID;
import static org.budgetanalyzer.transaction.util.TestConstants.PARSER_REVISION_ID;
import static org.budgetanalyzer.transaction.util.TestConstants.STATEMENT_FORMAT_ID;
import static org.budgetanalyzer.transaction.util.TestConstants.USER_ID;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.function.IntUnaryOperator;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.config.PreviewImportTokenProperties;

class PreviewImportTokenServiceTest {

  private static final String ENCRYPTION_SECRET = "test-preview-import-token-encryption-secret";
  private static final Instant NOW = Instant.parse("2026-05-11T12:00:00Z");
  private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
  private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

  @Test
  void createAndVerifyTokenValidTokenReturnsPayload() {
    var previewImportTokenService = service(NOW, Duration.ofMinutes(30));

    var token =
        previewImportTokenService.createToken(
            USER_ID,
            CONTENT_HASH,
            ORIGINAL_FILENAME,
            STATEMENT_FORMAT_ID,
            PARSER_REVISION_ID,
            ACCOUNT_ID,
            FILE_SIZE_BYTES);

    var result = previewImportTokenService.verifyToken(token, USER_ID);

    assertThat(result.ownerId()).isEqualTo(USER_ID);
    assertThat(result.contentHash()).isEqualTo(CONTENT_HASH);
    assertThat(result.originalFilename()).isEqualTo(ORIGINAL_FILENAME);
    assertThat(result.statementFormatId()).isEqualTo(STATEMENT_FORMAT_ID);
    assertThat(result.parserRevisionId()).isEqualTo(PARSER_REVISION_ID);
    assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
    assertThat(result.fileSizeBytes()).isEqualTo(FILE_SIZE_BYTES);
    assertThat(result.issuedAt()).isEqualTo(NOW);
    assertThat(result.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
  }

  @Test
  void createTokenTokenSegmentsDoNotRevealPayload() throws Exception {
    var previewImportTokenService = service(NOW, Duration.ofMinutes(30));

    var token =
        previewImportTokenService.createToken(
            USER_ID,
            CONTENT_HASH,
            ORIGINAL_FILENAME,
            STATEMENT_FORMAT_ID,
            PARSER_REVISION_ID,
            ACCOUNT_ID,
            FILE_SIZE_BYTES);
    var tokenSegments = token.split("\\.");

    assertThat(tokenSegments).hasSize(3);
    assertThat(tokenSegments[0]).isEqualTo("v2");
    assertDecodedSegmentDoesNotRevealPayload(tokenSegments[1]);
    assertDecodedSegmentDoesNotRevealPayload(tokenSegments[2]);
    assertThatDecodedSegmentIsNotValidJson(tokenSegments[2]);
  }

  @Test
  void verifyTokenTamperedInitializationVectorThrowsInvalidToken() {
    var previewImportTokenService = service(NOW, Duration.ofMinutes(30));
    var token =
        previewImportTokenService.createToken(
            USER_ID,
            CONTENT_HASH,
            ORIGINAL_FILENAME,
            STATEMENT_FORMAT_ID,
            PARSER_REVISION_ID,
            null,
            FILE_SIZE_BYTES);
    var tamperedToken = tamperDecodedByte(token, 1, segmentLength -> 0);

    assertAuthenticationRejected(previewImportTokenService, tamperedToken);
  }

  @Test
  void verifyTokenTamperedCiphertextThrowsInvalidToken() {
    var previewImportTokenService = service(NOW, Duration.ofMinutes(30));
    var token =
        previewImportTokenService.createToken(
            USER_ID,
            CONTENT_HASH,
            ORIGINAL_FILENAME,
            STATEMENT_FORMAT_ID,
            PARSER_REVISION_ID,
            null,
            FILE_SIZE_BYTES);
    var tamperedToken = tamperDecodedByte(token, 2, segmentLength -> 0);

    assertAuthenticationRejected(previewImportTokenService, tamperedToken);
  }

  @Test
  void verifyTokenTamperedAuthenticationTagThrowsInvalidToken() {
    var previewImportTokenService = service(NOW, Duration.ofMinutes(30));
    var token =
        previewImportTokenService.createToken(
            USER_ID,
            CONTENT_HASH,
            ORIGINAL_FILENAME,
            STATEMENT_FORMAT_ID,
            PARSER_REVISION_ID,
            null,
            FILE_SIZE_BYTES);
    var tamperedToken = tamperDecodedByte(token, 2, segmentLength -> segmentLength - 1);

    assertAuthenticationRejected(previewImportTokenService, tamperedToken);
  }

  @Test
  void verifyTokenExpiredTokenThrowsExpiredToken() {
    var previewImportTokenService = service(NOW, Duration.ofMinutes(30));
    var token =
        previewImportTokenService.createToken(
            USER_ID,
            CONTENT_HASH,
            ORIGINAL_FILENAME,
            STATEMENT_FORMAT_ID,
            PARSER_REVISION_ID,
            null,
            FILE_SIZE_BYTES);
    var expiredService = service(NOW.plus(Duration.ofMinutes(31)), Duration.ofMinutes(30));

    assertThatThrownBy(() -> expiredService.verifyToken(token, USER_ID))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getCode())
                    .isEqualTo(BudgetAnalyzerError.PREVIEW_IMPORT_TOKEN_EXPIRED.name()));
  }

  @Test
  void verifyTokenMissingRequiredFieldThrowsInvalidToken() throws Exception {
    var previewImportTokenService = service(NOW, Duration.ofMinutes(30));
    var token =
        encryptedToken(
            Map.of(
                "ownerId",
                USER_ID,
                "contentHash",
                CONTENT_HASH,
                "fileSizeBytes",
                FILE_SIZE_BYTES,
                "issuedAt",
                NOW.toString(),
                "expiresAt",
                NOW.plus(Duration.ofMinutes(30)).toString()));

    assertInvalidToken(previewImportTokenService, token);
  }

  @Test
  void verifyTokenWrongSegmentCountThrowsInvalidToken() {
    var previewImportTokenService = service(NOW, Duration.ofMinutes(30));

    assertInvalidToken(previewImportTokenService, "v2.only-two");
    assertInvalidToken(previewImportTokenService, "v2.too.many.segments");
  }

  @Test
  void verifyTokenWrongVersionThrowsInvalidToken() {
    var previewImportTokenService = service(NOW, Duration.ofMinutes(30));
    var token =
        previewImportTokenService.createToken(
            USER_ID,
            CONTENT_HASH,
            ORIGINAL_FILENAME,
            STATEMENT_FORMAT_ID,
            PARSER_REVISION_ID,
            null,
            FILE_SIZE_BYTES);
    var tokenSegments = token.split("\\.");
    tokenSegments[0] = "v1";
    var wrongVersionToken = String.join(".", tokenSegments);

    assertInvalidToken(previewImportTokenService, wrongVersionToken);
  }

  @Test
  void verifyTokenInvalidBase64ThrowsInvalidToken() {
    var previewImportTokenService = service(NOW, Duration.ofMinutes(30));

    assertInvalidToken(previewImportTokenService, "v2.not*base64.ciphertext");
  }

  @Test
  void verifyTokenDecryptFailureThrowsInvalidToken() {
    var previewImportTokenService = service(NOW, Duration.ofMinutes(30));
    var initializationVector =
        BASE64_URL_ENCODER.encodeToString("123456789012".getBytes(StandardCharsets.UTF_8));
    var ciphertext =
        BASE64_URL_ENCODER.encodeToString("not-encrypted".getBytes(StandardCharsets.UTF_8));

    assertInvalidToken(previewImportTokenService, "v2." + initializationVector + "." + ciphertext);
  }

  @Test
  void verifyTokenOwnerMismatchThrowsInvalidToken() {
    var previewImportTokenService = service(NOW, Duration.ofMinutes(30));
    var token =
        previewImportTokenService.createToken(
            USER_ID,
            CONTENT_HASH,
            ORIGINAL_FILENAME,
            STATEMENT_FORMAT_ID,
            PARSER_REVISION_ID,
            null,
            FILE_SIZE_BYTES);

    assertThatThrownBy(() -> previewImportTokenService.verifyToken(token, OTHER_USER_ID))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getCode())
                    .isEqualTo(BudgetAnalyzerError.PREVIEW_IMPORT_TOKEN_INVALID.name()));
  }

  private static PreviewImportTokenService service(Instant instant, Duration ttl) {
    return new PreviewImportTokenService(
        new PreviewImportTokenProperties(ENCRYPTION_SECRET, ttl),
        JSON_MAPPER,
        Clock.fixed(instant, ZoneOffset.UTC));
  }

  private static String encryptedToken(Map<String, Object> payload) throws Exception {
    var payloadJson = JSON_MAPPER.writeValueAsBytes(payload);
    var initializationVector = "123456789012".getBytes(StandardCharsets.UTF_8);
    var cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.ENCRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, initializationVector));
    cipher.updateAAD("v2".getBytes(StandardCharsets.UTF_8));
    return "v2."
        + BASE64_URL_ENCODER.encodeToString(initializationVector)
        + "."
        + BASE64_URL_ENCODER.encodeToString(cipher.doFinal(payloadJson));
  }

  private static SecretKeySpec encryptionKey() throws GeneralSecurityException {
    var normalizedKey =
        MessageDigest.getInstance("SHA-256")
            .digest(ENCRYPTION_SECRET.getBytes(StandardCharsets.UTF_8));
    return new SecretKeySpec(normalizedKey, "AES");
  }

  private static String tamperDecodedByte(
      String token, int segmentIndex, IntUnaryOperator byteIndexForSegmentLength) {
    var tokenSegments = token.split("\\.");
    var segmentBytes = BASE64_URL_DECODER.decode(tokenSegments[segmentIndex]);
    var byteIndex = byteIndexForSegmentLength.applyAsInt(segmentBytes.length);
    segmentBytes[byteIndex] ^= 0x01;
    tokenSegments[segmentIndex] = BASE64_URL_ENCODER.encodeToString(segmentBytes);

    return String.join(".", tokenSegments);
  }

  private static String decodedString(String tokenSegment) {
    return new String(BASE64_URL_DECODER.decode(tokenSegment), StandardCharsets.UTF_8);
  }

  private static void assertDecodedSegmentDoesNotRevealPayload(String tokenSegment) {
    assertThat(decodedString(tokenSegment))
        .doesNotContain(
            CONTENT_HASH,
            USER_ID,
            ORIGINAL_FILENAME,
            "capital-one",
            ACCOUNT_ID,
            "contentHash",
            "ownerId",
            "originalFilename",
            "statementFormatId",
            "parserRevisionId",
            "accountId");
  }

  private static void assertThatDecodedSegmentIsNotValidJson(String tokenSegment) {
    assertThatThrownBy(() -> JSON_MAPPER.readTree(BASE64_URL_DECODER.decode(tokenSegment)))
        .isInstanceOf(JacksonException.class);
  }

  private static void assertInvalidToken(
      PreviewImportTokenService previewImportTokenService, String token) {
    assertThatThrownBy(() -> previewImportTokenService.verifyToken(token, USER_ID))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getCode())
                    .isEqualTo(BudgetAnalyzerError.PREVIEW_IMPORT_TOKEN_INVALID.name()));
  }

  private static void assertAuthenticationRejected(
      PreviewImportTokenService previewImportTokenService, String token) {
    assertThatThrownBy(() -> previewImportTokenService.verifyToken(token, USER_ID))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> {
              assertThat(exception.getCode())
                  .isEqualTo(BudgetAnalyzerError.PREVIEW_IMPORT_TOKEN_INVALID.name());
              assertThat(exception).hasCauseInstanceOf(AEADBadTagException.class);
            });
  }
}
