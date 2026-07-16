package org.budgetanalyzer.transaction.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.stereotype.Service;

/** Service for computing file content hashes. */
@Service
public class FileHashService {

  private static final String SHA_256 = "SHA-256";

  /**
   * Computes the SHA-256 hash of file content that has already been read.
   *
   * @param fileContent the file bytes to hash
   * @return hex-encoded SHA-256 hash (64 characters)
   */
  public String computeHash(byte[] fileContent) {
    try {
      var digest = MessageDigest.getInstance(SHA_256);
      return bytesToHex(digest.digest(fileContent));
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
