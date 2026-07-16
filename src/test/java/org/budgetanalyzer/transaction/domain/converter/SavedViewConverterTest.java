package org.budgetanalyzer.transaction.domain.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;

import org.budgetanalyzer.transaction.domain.ViewCriteria;

class SavedViewConverterTest {

  private final ViewCriteriaConverter viewCriteriaConverter = new ViewCriteriaConverter();
  private final LongSetConverter longSetConverter = new LongSetConverter();

  @Test
  void viewCriteriaConverter_preservesExplicitEmptyCriteria() {
    var criteria = viewCriteriaConverter.convertToEntityAttribute("{}");

    assertThat(criteria).isEqualTo(ViewCriteria.empty());
  }

  @Test
  void viewCriteriaConverter_rejectsNullCriteriaPersistenceValues() {
    assertThatThrownBy(() -> viewCriteriaConverter.convertToDatabaseColumn(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("ViewCriteria must not be null");

    assertThatThrownBy(() -> viewCriteriaConverter.convertToEntityAttribute("null"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("ViewCriteria JSON must not be null");
  }

  @Test
  void longSetConverter_preservesExplicitEmptySet() {
    assertThat(longSetConverter.convertToDatabaseColumn(Set.of())).isEqualTo("[]");
    assertThat(longSetConverter.convertToEntityAttribute("[]")).isEmpty();
  }

  @Test
  void longSetConverter_rejectsNullAndBlankPersistenceValues() {
    assertThatThrownBy(() -> longSetConverter.convertToDatabaseColumn(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Long ID set must not be null");

    assertThatThrownBy(() -> longSetConverter.convertToEntityAttribute(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Failed to deserialize Set<Long> from JSON");
  }
}
