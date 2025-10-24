package com.bleurubin.budgetanalyzer.service;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SoftDeleteOperations<T, ID> {

  // Abstract method that implementations must provide
  JpaSpecificationExecutor<T> getRepository();

  // Default implementation of notDeleted specification
  default Specification<T> notDeleted() {
    return (root, query, cb) -> cb.equal(root.get("deleted"), false);
  }

  default List<T> findAll(Specification<T> spec) {
    return getRepository().findAll(notDeleted().and(spec));
  }

  default Page<T> findAll(Specification<T> spec, Pageable pageable) {
    return getRepository().findAll(notDeleted().and(spec), pageable);
  }

  default Optional<T> findOne(Specification<T> spec) {
    return getRepository().findOne(notDeleted().and(spec));
  }

  default long count(Specification<T> spec) {
    return getRepository().count(notDeleted().and(spec));
  }

  default Optional<T> findById(ID id) {
    return findOne((root, query, cb) -> cb.equal(root.get("id"), id));
  }

  default List<T> findAllActive() {
    return getRepository().findAll(notDeleted());
  }

  default Page<T> findAllActive(Pageable pageable) {
    return getRepository().findAll(notDeleted(), pageable);
  }
}
