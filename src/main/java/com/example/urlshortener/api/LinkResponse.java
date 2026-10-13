package com.example.urlshortener.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LinkResponse(
    String code, String shortUrl, String url, Instant createdAt, Instant expiresAt) {}
