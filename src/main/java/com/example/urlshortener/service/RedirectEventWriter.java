package com.example.urlshortener.service;

import com.example.urlshortener.domain.RedirectEventEntity;
import com.example.urlshortener.repository.RedirectEventRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RedirectEventWriter {

  private final RedirectEventRepository redirectEventRepository;

  public RedirectEventWriter(RedirectEventRepository redirectEventRepository) {
    this.redirectEventRepository = redirectEventRepository;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 2)
  public void record(String code, Instant occurredAt) {
    redirectEventRepository.saveAndFlush(new RedirectEventEntity(code, occurredAt));
  }
}
