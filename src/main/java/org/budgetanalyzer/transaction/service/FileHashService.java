package org.budgetanalyzer.transaction.service;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Service for computing file content hashes. */
@Service
public class FileHashService {

  private static final String SHA_256 = "SHA-256";
  private static final int BUFFER_SIZE = 8192;

  /**
   * Computes the SHA-256 hash of a file's content.
   *
   * @param file the multipart file to hash
   * @return hex-encoded SHA-256 hash (64 characters)
   * @throws IOException if reading the file fails
   */
  public String computeHash(MultipartFile file) throws IOException {
    try (InputStream inputStream = file.getInputStream()) {
      var digest = MessageDigest.getInstance(SHA_256);
      var buffer = new byte[BUFFER_SIZE];
      int bytesRead;
      while ((bytesRead = inputStream.read(buffer)) != -1) {
        digest.update(buffer, 0, bytesRead);
      }
      return bytesToHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is always available in Java
      throw new IllegalStateException("SHA-256 algorithm not available", e);
    }
  }

  private String bytesToHex(byte[] bytes) {
    var hexString = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      var hex = Integer.toHexString(0xff & b);
      if (hex.length() == 1) {
        hexString.append('0');
      }
      hexString.append(hex);
    }
    return hexString.toString();
  }
}
