package com.example.urlshortener.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

public record CreateLinkRequest(
    @NotBlank @Size(max = 2048) String url, @JsonProperty("expires_at") OffsetDateTime expiresAt) {}
