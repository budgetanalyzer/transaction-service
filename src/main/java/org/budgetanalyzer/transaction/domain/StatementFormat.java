package org.budgetanalyzer.transaction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

import org.budgetanalyzer.core.domain.AuditableEntity;

/**
 * Entity representing a configurable statement format for file imports.
 *
 * <p>Stores user-facing metadata for selecting a statement format. Parser details live in hidden
 * {@link ParserRevision} rows.
 */
@Entity
@Table(name = "statement_format")
public class StatementFormat extends AuditableEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Type of format (CSV, PDF, XLSX). */
  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "format_type", nullable = false, length = 10)
  private FormatType formatType;

  /** Bank name for transactions created from this format. */
  @NotNull
  @Column(name = "bank_name", nullable = false, length = 100)
  private String bankName;

  /** Default currency ISO code for transactions. */
  @NotNull
  @Column(name = "default_currency_iso_code", nullable = false, length = 3)
  private String defaultCurrencyIsoCode;

  /** User-friendly display name for UI dropdowns. */
  @NotNull
  @Column(name = "display_name", nullable = false, length = 100)
  private String displayName;

  /** Visibility scope for this statement format. */
  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "scope", nullable = false, length = 10)
  private StatementFormatScope scope = StatementFormatScope.USER;

  /** Owner ID for user-scoped formats; null for system formats. */
  @Column(name = "owner_id", length = 50)
  private String ownerId;

  /** Whether this format is enabled for use. */
  @NotNull
  @Column(name = "enabled", nullable = false)
  private boolean enabled = true;

  /** Default constructor for JPA. */
  protected StatementFormat() {}

  /**
   * Creates a new user-scoped CSV statement format.
   *
   * @param displayName user-friendly display name
   * @param bankName bank name for transactions
   * @param defaultCurrencyIsoCode default currency ISO code
   * @param ownerId user ID that owns the format
   * @return new StatementFormat configured for CSV
   */
  public static StatementFormat createCsvFormat(
      String displayName, String bankName, String defaultCurrencyIsoCode, String ownerId) {
    return createFormat(
        displayName,
        FormatType.CSV,
        bankName,
        defaultCurrencyIsoCode,
        StatementFormatScope.USER,
        ownerId);
  }

  /**
   * Creates a new system-scoped CSV statement format.
   *
   * @param displayName user-friendly display name
   * @param bankName bank name for transactions
   * @param defaultCurrencyIsoCode default currency ISO code
   * @return new StatementFormat configured for CSV
   */
  public static StatementFormat createSystemCsvFormat(
      String displayName, String bankName, String defaultCurrencyIsoCode) {
    return createFormat(
        displayName,
        FormatType.CSV,
        bankName,
        defaultCurrencyIsoCode,
        StatementFormatScope.SYSTEM,
        null);
  }

  /**
   * Creates a new user-scoped PDF statement format.
   *
   * @param displayName user-friendly display name
   * @param bankName bank name for transactions
   * @param defaultCurrencyIsoCode default currency ISO code
   * @param ownerId user ID that owns the format
   * @return new StatementFormat configured for PDF
   */
  public static StatementFormat createUserPdfFormat(
      String displayName, String bankName, String defaultCurrencyIsoCode, String ownerId) {
    return createFormat(
        displayName,
        FormatType.PDF,
        bankName,
        defaultCurrencyIsoCode,
        StatementFormatScope.USER,
        ownerId);
  }

  /**
   * Creates a new system-scoped PDF statement format.
   *
   * @param displayName user-friendly display name
   * @param bankName bank name for transactions
   * @param defaultCurrencyIsoCode default currency ISO code
   * @return new StatementFormat configured for PDF
   */
  public static StatementFormat createSystemPdfFormat(
      String displayName, String bankName, String defaultCurrencyIsoCode) {
    return createFormat(
        displayName,
        FormatType.PDF,
        bankName,
        defaultCurrencyIsoCode,
        StatementFormatScope.SYSTEM,
        null);
  }

  private static StatementFormat createFormat(
      String displayName,
      FormatType formatType,
      String bankName,
      String defaultCurrencyIsoCode,
      StatementFormatScope scope,
      String ownerId) {
    var format = new StatementFormat();
    format.displayName = displayName;
    format.formatType = formatType;
    format.bankName = bankName;
    format.defaultCurrencyIsoCode = defaultCurrencyIsoCode;
    format.scope = scope;
    format.ownerId = ownerId;
    format.enabled = true;
    return format;
  }

  public Long getId() {
    return id;
  }

  public FormatType getFormatType() {
    return formatType;
  }

  public void setFormatType(FormatType formatType) {
    this.formatType = formatType;
  }

  public String getBankName() {
    return bankName;
  }

  public void setBankName(String bankName) {
    this.bankName = bankName;
  }

  public String getDefaultCurrencyIsoCode() {
    return defaultCurrencyIsoCode;
  }

  public void setDefaultCurrencyIsoCode(String defaultCurrencyIsoCode) {
    this.defaultCurrencyIsoCode = defaultCurrencyIsoCode;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public StatementFormatScope getScope() {
    return scope;
  }

  public void setScope(StatementFormatScope scope) {
    this.scope = scope;
  }

  public String getOwnerId() {
    return ownerId;
  }

  public void setOwnerId(String ownerId) {
    this.ownerId = ownerId;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }
}
