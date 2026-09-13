package com.example.extraction.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aws")
public record AwsProperties(
    String region,
    String accessKey,
    String secretKey
) {
  public AwsProperties {
    if (region == null || region.isBlank()) {
      throw new IllegalArgumentException("AWS region must not be empty");
    }
    if (accessKey == null || accessKey.isBlank()) {
      throw new IllegalArgumentException("AWS accessKey must not be empty");
    }
    if (secretKey == null || secretKey.isBlank()) {
      throw new IllegalArgumentException("AWS secretKey must not be empty");
    }
  }
}
