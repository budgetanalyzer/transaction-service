package org.budgetanalyzer.transaction.domain.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.budgetanalyzer.transaction.domain.ViewCriteria;

/** JPA converter for storing {@link ViewCriteria} as JSON text. */
@Converter
public class ViewCriteriaConverter implements AttributeConverter<ViewCriteria, String> {

  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper().registerModule(new JavaTimeModule());

  @Override
  public String convertToDatabaseColumn(ViewCriteria criteria) {
    if (criteria == null) {
      return "{}";
    }
    try {
      return OBJECT_MAPPER.writeValueAsString(criteria);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Failed to serialize ViewCriteria to JSON", e);
    }
  }

  @Override
  public ViewCriteria convertToEntityAttribute(String json) {
    if (json == null || json.isBlank()) {
      return ViewCriteria.empty();
    }
    try {
      return OBJECT_MAPPER.readValue(json, ViewCriteria.class);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Failed to deserialize ViewCriteria from JSON", e);
    }
  }
}
