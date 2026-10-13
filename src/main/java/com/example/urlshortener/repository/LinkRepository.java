package com.example.urlshortener.repository;

import com.example.urlshortener.domain.LinkEntity;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.transaction.annotation.Transactional;

public interface LinkRepository extends JpaRepository<LinkEntity, String> {

  @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"))
  boolean existsByCode(String code);

  @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"))
  Optional<LinkEntity> findByCode(String code);

  @Override
  @Transactional(timeout = 2)
  <S extends LinkEntity> S saveAndFlush(S entity);
}
