package com.example.urlshortener.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ShortenerMetrics {

  private final MeterRegistry meterRegistry;

  public ShortenerMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  public void linkCreated() {
    meterRegistry.counter("shortener.links.created").increment();
  }

  public void redirectResolved() {
    meterRegistry.counter("shortener.redirects", "outcome", "success").increment();
  }

  public void redirectFailed(HttpStatus status) {
    meterRegistry
        .counter(
            "shortener.redirects", "outcome", "error", "status", Integer.toString(status.value()))
        .increment();
  }
}
