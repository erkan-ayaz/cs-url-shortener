package com.example.urlshortener.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.link")
public record LinkProperties(
    String publicBaseUrl,
    Duration defaultTtl,
    Duration maxTtl,
    Duration maxAnalyticsRange,
    int maxUrlLength) {}
