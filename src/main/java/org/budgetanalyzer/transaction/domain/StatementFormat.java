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

/**
 * Entity representing a configurable statement format for file imports.
 *
 * <p>Stores configuration for parsing different bank statement formats (CSV, PDF, XLSX). CSV
 * formats use the column configuration fields, while PDF formats are handled by dedicated
 * extractors (the entity just stores metadata).
 */
@Entity
@Table(name = "statement_format")
public class StatementFormat {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Unique format identifier (e.g., "capital-one-bank-csv", "bkk-bank-csv"). */
  @NotNull
  @Column(name = "format_key", unique = true, nullable = false, length = 50)
  private String formatKey;

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

  // ===== CSV-specific fields (null for PDF/XLSX) =====

  /** CSV column header for date field. */
  @Column(name = "date_header", length = 50)
  private String dateHeader;

  /** Date format pattern (e.g., "MM/dd/uu", "d MMM uuuu"). */
  @Column(name = "date_format", length = 50)
  private String dateFormat;

  /** CSV column header for description field. */
  @Column(name = "description_header", length = 50)
  private String descriptionHeader;

  /** CSV column header for credit amount (or combined amount column). */
  @Column(name = "credit_header", length = 50)
  private String creditHeader;

  /** CSV column header for debit amount (or combined amount column). */
  @Column(name = "debit_header", length = 50)
  private String debitHeader;

  /** CSV column header for explicit transaction type (nullable). */
  @Column(name = "type_header", length = 50)
  private String typeHeader;

  /** CSV column header for category (nullable). */
  @Column(name = "category_header", length = 50)
  private String categoryHeader;

  /** Whether this format is enabled for use. */
  @NotNull
  @Column(name = "enabled", nullable = false)
  private boolean enabled = true;

  /** Default constructor for JPA. */
  protected StatementFormat() {}

  /**
   * Creates a new CSV statement format.
   *
   * @param formatKey unique format identifier
   * @param displayName user-friendly display name
   * @param bankName bank name for transactions
   * @param defaultCurrencyIsoCode default currency ISO code
   * @param dateHeader CSV column header for date
   * @param dateFormat date format pattern
   * @param descriptionHeader CSV column header for description
   * @param creditHeader CSV column header for credit amount
   * @param debitHeader CSV column header for debit amount
   * @param typeHeader CSV column header for type (nullable)
   * @param categoryHeader CSV column header for category (nullable)
   * @return new StatementFormat configured for CSV
   */
  public static StatementFormat createCsvFormat(
      String formatKey,
      String displayName,
      String bankName,
      String defaultCurrencyIsoCode,
      String dateHeader,
      String dateFormat,
      String descriptionHeader,
      String creditHeader,
      String debitHeader,
      String typeHeader,
      String categoryHeader) {
    var format = new StatementFormat();
    format.formatKey = formatKey;
    format.displayName = displayName;
    format.formatType = FormatType.CSV;
    format.bankName = bankName;
    format.defaultCurrencyIsoCode = defaultCurrencyIsoCode;
    format.dateHeader = dateHeader;
    format.dateFormat = dateFormat;
    format.descriptionHeader = descriptionHeader;
    format.creditHeader = creditHeader;
    format.debitHeader = debitHeader;
    format.typeHeader = typeHeader;
    format.categoryHeader = categoryHeader;
    format.enabled = true;
    return format;
  }

  /**
   * Creates a new PDF statement format (metadata only, extraction handled by dedicated extractor).
   *
   * @param formatKey unique format identifier
   * @param displayName user-friendly display name
   * @param bankName bank name for transactions
   * @param defaultCurrencyIsoCode default currency ISO code
   * @return new StatementFormat configured for PDF
   */
  public static StatementFormat createPdfFormat(
      String formatKey, String displayName, String bankName, String defaultCurrencyIsoCode) {
    var format = new StatementFormat();
    format.formatKey = formatKey;
    format.displayName = displayName;
    format.formatType = FormatType.PDF;
    format.bankName = bankName;
    format.defaultCurrencyIsoCode = defaultCurrencyIsoCode;
    format.enabled = true;
    return format;
  }

  public Long getId() {
    return id;
  }

  public String getFormatKey() {
    return formatKey;
  }

  public void setFormatKey(String formatKey) {
    this.formatKey = formatKey;
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

  public String getDateHeader() {
    return dateHeader;
  }

  public void setDateHeader(String dateHeader) {
    this.dateHeader = dateHeader;
  }

  public String getDateFormat() {
    return dateFormat;
  }

  public void setDateFormat(String dateFormat) {
    this.dateFormat = dateFormat;
  }

  public String getDescriptionHeader() {
    return descriptionHeader;
  }

  public void setDescriptionHeader(String descriptionHeader) {
    this.descriptionHeader = descriptionHeader;
  }

  public String getCreditHeader() {
    return creditHeader;
  }

  public void setCreditHeader(String creditHeader) {
    this.creditHeader = creditHeader;
  }

  public String getDebitHeader() {
    return debitHeader;
  }

  public void setDebitHeader(String debitHeader) {
    this.debitHeader = debitHeader;
  }

  public String getTypeHeader() {
    return typeHeader;
  }

  public void setTypeHeader(String typeHeader) {
    this.typeHeader = typeHeader;
  }

  public String getCategoryHeader() {
    return categoryHeader;
  }

  public void setCategoryHeader(String categoryHeader) {
    this.categoryHeader = categoryHeader;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }
}
