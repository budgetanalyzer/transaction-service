package org.budgetanalyzer.transaction.service.dto;

/**
 * Service-layer patch for updating a statement format.
 *
 * <p>All fields are optional - only non-null fields will be updated.
 *
 * @param displayName user-friendly display name
 * @param bankName bank name for transactions created from this format
 * @param defaultCurrencyIsoCode default currency ISO code
 * @param enabled whether this format is enabled for use
 */
public record StatementFormatPatch(
    String displayName, String bankName, String defaultCurrencyIsoCode, Boolean enabled) {

  /**
   * Legacy constructor that ignores parser configuration fields now stored on parser revisions.
   *
   * @param displayName user-friendly display name
   * @param bankName bank name for transactions created from this format
   * @param defaultCurrencyIsoCode default currency ISO code
   * @param dateHeader ignored legacy CSV column header
   * @param dateFormat ignored legacy CSV date format
   * @param descriptionHeader ignored legacy CSV column header
   * @param creditHeader ignored legacy CSV column header
   * @param debitHeader ignored legacy CSV column header
   * @param typeHeader ignored legacy CSV column header
   * @param categoryHeader ignored legacy CSV column header
   * @param enabled whether this format is enabled for use
   */
  public StatementFormatPatch(
      String displayName,
      String bankName,
      String defaultCurrencyIsoCode,
      String dateHeader,
      String dateFormat,
      String descriptionHeader,
      String creditHeader,
      String debitHeader,
      String typeHeader,
      String categoryHeader,
      Boolean enabled) {
    this(displayName, bankName, defaultCurrencyIsoCode, enabled);
  }
}
