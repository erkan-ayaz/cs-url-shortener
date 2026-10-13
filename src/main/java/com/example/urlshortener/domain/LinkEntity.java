package com.example.urlshortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "links")
public class LinkEntity {

  @Id
  @Column(name = "code", nullable = false, length = 8)
  private String code;

  @Column(name = "destination_url", nullable = false, length = 2048)
  private String destinationUrl;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "disabled_at")
  private Instant disabledAt;

  protected LinkEntity() {}

  public LinkEntity(String code, String destinationUrl, Instant createdAt, Instant expiresAt) {
    this.code = code;
    this.destinationUrl = destinationUrl;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
  }

  public String getCode() {
    return code;
  }

  public String getDestinationUrl() {
    return destinationUrl;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getDisabledAt() {
    return disabledAt;
  }
}
