package com.example.extraction.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "extraction")
public record ExtractionProperties(
    long maxDocumentSizeBytes,
    int maxPages,
    int maxConcurrency,
    Duration requestTimeout,
    String screenshotOutputDir
) {
  public ExtractionProperties {
    if (maxDocumentSizeBytes <= 0) {
      throw new IllegalArgumentException("maxDocumentSizeBytes must be > 0");
    }
    if (maxPages <= 0) {
      throw new IllegalArgumentException("maxPages must be > 0");
    }
    if (maxConcurrency <= 0) {
      throw new IllegalArgumentException("maxConcurrency must be > 0");
    }
    if (requestTimeout.isNegative() || requestTimeout.isZero()) {
      throw new IllegalArgumentException("requestTimeout must be positive");
    }
  }
}
