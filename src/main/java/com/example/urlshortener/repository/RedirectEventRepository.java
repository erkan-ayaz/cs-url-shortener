package com.example.urlshortener.repository;

import com.example.urlshortener.domain.RedirectEventEntity;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.QueryHints;

public interface RedirectEventRepository extends JpaRepository<RedirectEventEntity, Long> {

  @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"))
  long countByLinkCodeAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
      String linkCode, Instant from, Instant to);
}
