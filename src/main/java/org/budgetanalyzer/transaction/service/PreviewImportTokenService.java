package org.budgetanalyzer.transaction.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.config.PreviewImportTokenProperties;
import org.budgetanalyzer.transaction.service.dto.PreviewImportToken;

/** Service for creating and verifying opaque preview import tokens. */
@Service
public class PreviewImportTokenService {

  private static final String TOKEN_VERSION = "v2";
  private static final String ENCRYPTION_ALGORITHM = "AES";
  private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int AUTHENTICATION_TAG_BITS = 128;
  private static final int INITIALIZATION_VECTOR_SIZE_BYTES = 12;
  private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
  private static final byte[] TOKEN_VERSION_AUTHENTICATED_DATA =
      TOKEN_VERSION.getBytes(StandardCharsets.UTF_8);

  private final PreviewImportTokenProperties previewImportTokenProperties;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final SecureRandom secureRandom = new SecureRandom();

  /**
   * Constructs a new PreviewImportTokenService.
   *
   * @param previewImportTokenProperties token encryption and expiration configuration
   * @param objectMapper the JSON mapper used for token payloads
   * @param clock the clock used for issued and expiration timestamps
   */
  public PreviewImportTokenService(
      PreviewImportTokenProperties previewImportTokenProperties,
      ObjectMapper objectMapper,
      Clock clock) {
    this.previewImportTokenProperties = previewImportTokenProperties;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  /**
   * Creates an encrypted preview import token for an uploaded source file.
   *
   * @param ownerId the authenticated owner ID
   * @param contentHash the SHA-256 file content hash
   * @param originalFilename the uploaded file's original filename
   * @param detectedFormat the format key used to parse the file
   * @param accountId optional account ID applied during preview
   * @param fileSizeBytes uploaded file size in bytes
   * @return opaque encrypted preview import token
   */
  public String createToken(
      String ownerId,
      String contentHash,
      String originalFilename,
      String detectedFormat,
      String accountId,
      Long fileSizeBytes) {
    var issuedAt = clock.instant();
    var expiresAt = issuedAt.plus(previewImportTokenProperties.ttl());
    var previewImportToken =
        new PreviewImportToken(
            ownerId,
            contentHash,
            originalFilename,
            detectedFormat,
            accountId,
            fileSizeBytes,
            issuedAt,
            expiresAt);

    return encode(previewImportToken);
  }

  /**
   * Verifies an encrypted preview import token for the authenticated owner.
   *
   * @param token the token to verify
   * @param ownerId the authenticated owner ID expected in the token
   * @return verified preview import token metadata
   * @throws BusinessException when the token is invalid, expired, incomplete, or belongs to another
   *     owner
   */
  public PreviewImportToken verifyToken(String token, String ownerId) {
    var previewImportToken = decodeAndVerify(token);
    validateRequiredFields(previewImportToken);

    if (!previewImportToken.ownerId().equals(ownerId)) {
      throw invalidToken("Preview import token owner does not match the authenticated user.");
    }
    if (clock.instant().isAfter(previewImportToken.expiresAt())) {
      throw new BusinessException(
          "Preview import token has expired.",
          BudgetAnalyzerError.PREVIEW_IMPORT_TOKEN_EXPIRED.name());
    }

    return previewImportToken;
  }

  private String encode(PreviewImportToken previewImportToken) {
    try {
      var payloadJson = objectMapper.writeValueAsBytes(previewImportToken);
      var initializationVector = newInitializationVector();
      var ciphertext = encrypt(payloadJson, initializationVector);
      return TOKEN_VERSION
          + "."
          + BASE64_URL_ENCODER.encodeToString(initializationVector)
          + "."
          + BASE64_URL_ENCODER.encodeToString(ciphertext);
    } catch (GeneralSecurityException | JsonProcessingException e) {
      throw invalidToken("Failed to create preview import token.", e);
    }
  }

  private PreviewImportToken decodeAndVerify(String token) {
    if (token == null || token.isBlank()) {
      throw invalidToken("Preview import token is required.");
    }

    var parts = token.split("\\.", -1);
    if (parts.length != 3
        || !TOKEN_VERSION.equals(parts[0])
        || parts[1].isBlank()
        || parts[2].isBlank()) {
      throw invalidToken("Preview import token format is invalid.");
    }

    try {
      var initializationVector = decodeBase64(parts[1]);
      if (initializationVector.length != INITIALIZATION_VECTOR_SIZE_BYTES) {
        throw invalidToken("Preview import token initialization vector is invalid.");
      }
      var ciphertext = decodeBase64(parts[2]);
      var payloadJson = decrypt(ciphertext, initializationVector);
      return objectMapper.readValue(payloadJson, PreviewImportToken.class);
    } catch (GeneralSecurityException | IOException e) {
      throw invalidToken("Preview import token payload is invalid.", e);
    }
  }

  private byte[] encrypt(byte[] payloadJson, byte[] initializationVector)
      throws GeneralSecurityException {
    var cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
    cipher.init(
        Cipher.ENCRYPT_MODE,
        encryptionKey(),
        new GCMParameterSpec(AUTHENTICATION_TAG_BITS, initializationVector));
    cipher.updateAAD(TOKEN_VERSION_AUTHENTICATED_DATA);
    return cipher.doFinal(payloadJson);
  }

  private byte[] decrypt(byte[] ciphertext, byte[] initializationVector)
      throws GeneralSecurityException {
    var cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
    cipher.init(
        Cipher.DECRYPT_MODE,
        encryptionKey(),
        new GCMParameterSpec(AUTHENTICATION_TAG_BITS, initializationVector));
    cipher.updateAAD(TOKEN_VERSION_AUTHENTICATED_DATA);
    return cipher.doFinal(ciphertext);
  }

  private SecretKeySpec encryptionKey() throws GeneralSecurityException {
    var normalizedKey =
        MessageDigest.getInstance("SHA-256")
            .digest(
                previewImportTokenProperties.encryptionSecret().getBytes(StandardCharsets.UTF_8));
    return new SecretKeySpec(normalizedKey, ENCRYPTION_ALGORITHM);
  }

  private byte[] newInitializationVector() {
    var initializationVector = new byte[INITIALIZATION_VECTOR_SIZE_BYTES];
    secureRandom.nextBytes(initializationVector);
    return initializationVector;
  }

  private byte[] decodeBase64(String value) {
    try {
      return BASE64_URL_DECODER.decode(value);
    } catch (IllegalArgumentException e) {
      throw invalidToken("Preview import token encoding is invalid.", e);
    }
  }

  private void validateRequiredFields(PreviewImportToken previewImportToken) {
    if (isBlank(previewImportToken.ownerId())
        || isBlank(previewImportToken.contentHash())
        || isBlank(previewImportToken.originalFilename())
        || isBlank(previewImportToken.detectedFormat())
        || previewImportToken.fileSizeBytes() == null
        || previewImportToken.fileSizeBytes() < 0
        || previewImportToken.issuedAt() == null
        || previewImportToken.expiresAt() == null) {
      throw invalidToken("Preview import token is missing required fields.");
    }
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private BusinessException invalidToken(String message) {
    return new BusinessException(message, BudgetAnalyzerError.PREVIEW_IMPORT_TOKEN_INVALID.name());
  }

  private BusinessException invalidToken(String message, Exception cause) {
    return new BusinessException(
        message, BudgetAnalyzerError.PREVIEW_IMPORT_TOKEN_INVALID.name(), cause);
  }
}
