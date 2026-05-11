package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.config.PreviewImportTokenProperties;

class PreviewImportTokenServiceTest {

  private static final String SIGNING_SECRET = "test-preview-import-token-signing-secret";
  private static final Instant NOW = Instant.parse("2026-05-11T12:00:00Z");
  private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

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
  void verifyToken_badSignature_throwsInvalidToken() {
    var previewImportTokenService = service(NOW, Duration.ofMinutes(30));
    var token =
        previewImportTokenService.createToken(
            "usr_test123", "hash-123", "statement.csv", "capital-one", null, 128L);
    var replacement = token.endsWith("x") ? "y" : "x";
    var tamperedToken = token.substring(0, token.length() - 1) + replacement;

    assertThatThrownBy(() -> previewImportTokenService.verifyToken(tamperedToken, "usr_test123"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getCode())
                    .isEqualTo(BudgetAnalyzerError.PREVIEW_IMPORT_TOKEN_INVALID.name()));
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
        signedToken(
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

    assertThatThrownBy(() -> previewImportTokenService.verifyToken(token, "usr_test123"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getCode())
                    .isEqualTo(BudgetAnalyzerError.PREVIEW_IMPORT_TOKEN_INVALID.name()));
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
        new PreviewImportTokenProperties(SIGNING_SECRET, ttl),
        JsonMapper.builder().addModule(new JavaTimeModule()).build(),
        Clock.fixed(instant, ZoneOffset.UTC));
  }

  private static String signedToken(Map<String, Object> payload) throws Exception {
    var payloadJson = JsonMapper.builder().build().writeValueAsBytes(payload);
    var encodedPayload = BASE64_URL_ENCODER.encodeToString(payloadJson);
    var signedContent = "v1." + encodedPayload;
    return signedContent + "." + BASE64_URL_ENCODER.encodeToString(sign(signedContent));
  }

  private static byte[] sign(String signedContent)
      throws NoSuchAlgorithmException, InvalidKeyException {
    var mac = Mac.getInstance("HmacSHA256");
    var secretKeySpec =
        new SecretKeySpec(SIGNING_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    mac.init(secretKeySpec);
    return mac.doFinal(signedContent.getBytes(StandardCharsets.UTF_8));
  }
}
