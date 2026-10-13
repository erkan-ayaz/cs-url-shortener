package com.example.urlshortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "redirect_events")
public class RedirectEventEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "link_code", nullable = false, length = 8)
  private String linkCode;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  protected RedirectEventEntity() {}

  public RedirectEventEntity(String linkCode, Instant occurredAt) {
    this.linkCode = linkCode;
    this.occurredAt = occurredAt;
  }
}
