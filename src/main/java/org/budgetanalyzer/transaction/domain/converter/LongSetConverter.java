package org.budgetanalyzer.transaction.domain.converter;

import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** JPA converter for storing {@code Set<Long>} as JSON array string in the database. */
@Converter
public class LongSetConverter implements AttributeConverter<Set<Long>, String> {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final TypeReference<Set<Long>> SET_TYPE_REF = new TypeReference<>() {};

  @Override
  public String convertToDatabaseColumn(Set<Long> attribute) {
    if (attribute == null || attribute.isEmpty()) {
      return "[]";
    }
    try {
      return OBJECT_MAPPER.writeValueAsString(attribute);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Failed to serialize Set<Long> to JSON", e);
    }
  }

  @Override
  public Set<Long> convertToEntityAttribute(String dbData) {
    if (dbData == null || dbData.isBlank() || "[]".equals(dbData)) {
      return new HashSet<>();
    }
    try {
      return new HashSet<>(OBJECT_MAPPER.readValue(dbData, SET_TYPE_REF));
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Failed to deserialize Set<Long> from JSON", e);
    }
  }
}
