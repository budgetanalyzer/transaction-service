package org.budgetanalyzer.transaction.util;

/** Shared constants for transaction-service tests. */
public final class TestConstants {

  public static final long CONCURRENT_OPERATION_TIMEOUT_SECONDS = 30L;
  public static final String PERMISSION_VIEWS_READ = "views:read";
  public static final String PERMISSION_VIEWS_WRITE = "views:write";

  public static final String USER_ID = "usr_test123";
  public static final String OTHER_USER_ID = "usr_other789";

  public static final String CONTENT_HASH = "hash-123";
  public static final String ORIGINAL_FILENAME = "statement.csv";
  public static final Long STATEMENT_FORMAT_ID = 42L;
  public static final Long PARSER_REVISION_ID = 101L;
  public static final String ACCOUNT_ID = "checking-12345";
  public static final Long FILE_SIZE_BYTES = 128L;

  private TestConstants() {}
}
