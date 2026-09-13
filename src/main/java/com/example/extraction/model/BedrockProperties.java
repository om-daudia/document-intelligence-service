package com.example.extraction.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aws.bedrock")
public record BedrockProperties(
    String modelId,
    String s3Bucket
) {
  public BedrockProperties {
    if (modelId == null || modelId.isBlank()) {
      throw new IllegalArgumentException("Bedrock modelId must not be empty");
    }
    if (s3Bucket == null || s3Bucket.isBlank()) {
      throw new IllegalArgumentException("S3 bucket must not be empty");
    }
  }
}
