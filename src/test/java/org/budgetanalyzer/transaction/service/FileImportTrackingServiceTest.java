package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.budgetanalyzer.transaction.repository.FileImportRepository;

@ExtendWith(MockitoExtension.class)
class FileImportTrackingServiceTest {

  private static final String USER_ID = "user-123";
  private static final String SHA_256_OF_ABC =
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

  @Mock private FileImportRepository fileImportRepository;

  @Test
  void checkFile_byteContent_hashesBytesAndLooksUpCurrentUserOnly() {
    var fileHashService = new FileHashService();
    var fileImportTrackingService =
        new FileImportTrackingService(fileImportRepository, fileHashService);
    var fileContent = "abc".getBytes(StandardCharsets.UTF_8);

    when(fileImportRepository.findByContentHashAndImportedBy(SHA_256_OF_ABC, USER_ID))
        .thenReturn(Optional.empty());

    var result = fileImportTrackingService.checkFile(fileContent, USER_ID);

    assertThat(result.hash()).isEqualTo(SHA_256_OF_ABC);
    assertThat(result.existingImport()).isEmpty();
    verify(fileImportRepository).findByContentHashAndImportedBy(SHA_256_OF_ABC, USER_ID);
  }
}
