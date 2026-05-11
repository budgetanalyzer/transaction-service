package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.config.PreviewImportTokenProperties;

class PreviewImportTokenServiceTest {

  private static final String ENCRYPTION_SECRET = "test-preview-import-token-encryption-secret";
  private static final Instant NOW = Instant.parse("2026-05-11T12:00:00Z");
  private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
  private static final ObjectMapper OBJECT_MAPPER =
      JsonMapper.builder().addModule(new JavaTimeModule()).build();

  @Test
  void createAndVerifyToken_validToken_returnsPayload() {
    var previewImportTokenService = service(NOW, Duration.ofMinutes(30));

    var token =
        previewImportTokenService.createToken(
            "usr_test123", "hash-123", "statement.csv", "capital-one", "checking-12345", 128L);

    var result = previewImportTokenService.verifyToken(token, "usr_test123");

    assertThat(result.ownerId()).isEqualTo("usr_test123");
    assertThat(result.contentHash()).isEqualTo("hash-123");
    assertThat(result.originalFilename()).isEqualTo("statement.csv");
    assertThat(result.detectedFormat()).isEqualTo("capital-one");
    assertThat(result.accountId()).isEqualTo("checking-12345");
    assertThat(result.fileSizeBytes()).isEqualTo(128L);
    assertThat(result.issuedAt()).isEqualTo(NOW);
    assertThat(result.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
  }

  @Test
  void createToken_tokenSegmentsDoNotRevealPayload() throws Exception {
    var previewImportTokenService = service(NOW, Duration.ofMinutes(30));

    var token =
        previewImportTokenService.createToken(
            "usr_test123", "hash-123", "statement.csv", "capital-one", "checking-12345", 128L);
    var tokenSegments = token.split("\\.");

    assertThat(tokenSegments).hasSize(3);
    assertThat(tokenSegments[0]).isEqualTo("v2");
    assertThat(decodedString(tokenSegments[1]))
        .doesNotContain("hash-123", "usr_test123", "statement.csv", "contentHash");
    assertThat(decodedString(tokenSegments[2]))
        .doesNotContain("hash-123", "usr_test123", "statement.csv", "contentHash");
    assertThatThrownBy(() -> OBJECT_MAPPER.readTree(BASE64_URL_DECODER.decode(tokenSegments[2])))
        .isInstanceOf(JsonProcessingException.class);
  }

  @Test
  void verifyToken_tamperedCiphertext_throwsInvalidToken() {
    var previewImportTokenService = service(NOW, Duration.ofMinutes(30));
    var token =
        previewImportTokenService.createToken(
            "usr_test123", "hash-123", "statement.csv", "capital-one", null, 128L);
    var replacement = token.endsWith("x") ? "y" : "x";
    var tamperedToken = token.substring(0, token.length() - 1) + replacement;

    assertInvalidToken(previewImportTokenService, tamperedToken);
  }

  @Test
  void verifyToken_tamperedInitializationVector_throwsInvalidToken() {
    var previewImportTokenService = service(NOW, Duration.ofMinutes(30));
    var token =
        previewImportTokenService.createToken(
            "usr_test123", "hash-123", "statement.csv", "capital-one", null, 128L);
    var tokenSegments = token.split("\\.");
    var replacement = tokenSegments[1].endsWith("x") ? "y" : "x";
    tokenSegments[1] = tokenSegments[1].substring(0, tokenSegments[1].length() - 1) + replacement;
    var tamperedToken = String.join(".", tokenSegments);

    assertInvalidToken(previewImportTokenService, tamperedToken);
  }

  @Test
  void verifyToken_expiredToken_throwsExpiredToken() {
    var previewImportTokenService = service(NOW, Duration.ofMinutes(30));
    var token =
        previewImportTokenService.createToken(
            "usr_test123", "hash-123", "statement.csv", "capital-one", null, 128L);
    var expiredService = service(NOW.plus(Duration.ofMinutes(31)), Duration.ofMinutes(30));

    assertThatThrownBy(() -> expiredService.verifyToken(token, "usr_test123"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getCode())
                    .isEqualTo(BudgetAnalyzerError.PREVIEW_IMPORT_TOKEN_EXPIRED.name()));
  }

  @Test
  void verifyToken_missingRequiredField_throwsInvalidToken() throws Exception {
    var previewImportTokenService = service(NOW, Duration.ofMinutes(30));
    var token =
        encryptedToken(
            Map.of(
                "ownerId",
                "usr_test123",
                "contentHash",
                "hash-123",
                "detectedFormat",
                "capital-one",
                "fileSizeBytes",
                128,
                "issuedAt",
                NOW.toString(),
                "expiresAt",
                NOW.plus(Duration.ofMinutes(30)).toString()));

    assertInvalidToken(previewImportTokenService, token);
  }

  @Test
  void verifyToken_wrongSegmentCount_throwsInvalidToken() {
    var previewImportTokenService = service(NOW, Duration.ofMinutes(30));

    assertInvalidToken(previewImportTokenService, "v2.only-two");
    assertInvalidToken(previewImportTokenService, "v2.too.many.segments");
  }

  @Test
  void verifyToken_wrongVersion_throwsInvalidToken() {
    var previewImportTokenService = service(NOW, Duration.ofMinutes(30));
    var token =
        previewImportTokenService.createToken(
            "usr_test123", "hash-123", "statement.csv", "capital-one", null, 128L);
    var tokenSegments = token.split("\\.");
    tokenSegments[0] = "v1";
    var wrongVersionToken = String.join(".", tokenSegments);

    assertInvalidToken(previewImportTokenService, wrongVersionToken);
  }

  @Test
  void verifyToken_invalidBase64_throwsInvalidToken() {
    var previewImportTokenService = service(NOW, Duration.ofMinutes(30));

    assertInvalidToken(previewImportTokenService, "v2.not*base64.ciphertext");
  }

  @Test
  void verifyToken_decryptFailure_throwsInvalidToken() {
    var previewImportTokenService = service(NOW, Duration.ofMinutes(30));
    var initializationVector =
        BASE64_URL_ENCODER.encodeToString("123456789012".getBytes(StandardCharsets.UTF_8));
    var ciphertext =
        BASE64_URL_ENCODER.encodeToString("not-encrypted".getBytes(StandardCharsets.UTF_8));

    assertInvalidToken(previewImportTokenService, "v2." + initializationVector + "." + ciphertext);
  }

  @Test
  void verifyToken_ownerMismatch_throwsInvalidToken() {
    var previewImportTokenService = service(NOW, Duration.ofMinutes(30));
    var token =
        previewImportTokenService.createToken(
            "usr_test123", "hash-123", "statement.csv", "capital-one", null, 128L);

    assertThatThrownBy(() -> previewImportTokenService.verifyToken(token, "usr_other789"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getCode())
                    .isEqualTo(BudgetAnalyzerError.PREVIEW_IMPORT_TOKEN_INVALID.name()));
  }

  private static PreviewImportTokenService service(Instant instant, Duration ttl) {
    return new PreviewImportTokenService(
        new PreviewImportTokenProperties(ENCRYPTION_SECRET, ttl),
        OBJECT_MAPPER,
        Clock.fixed(instant, ZoneOffset.UTC));
  }

  private static String encryptedToken(Map<String, Object> payload) throws Exception {
    var payloadJson = OBJECT_MAPPER.writeValueAsBytes(payload);
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

  private static String decodedString(String tokenSegment) {
    return new String(BASE64_URL_DECODER.decode(tokenSegment), StandardCharsets.UTF_8);
  }

  private static void assertInvalidToken(
      PreviewImportTokenService previewImportTokenService, String token) {
    assertThatThrownBy(() -> previewImportTokenService.verifyToken(token, "usr_test123"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getCode())
                    .isEqualTo(BudgetAnalyzerError.PREVIEW_IMPORT_TOKEN_INVALID.name()));
  }
}
