package com.example.extraction.config;

import com.example.extraction.model.AwsProperties;
import com.example.extraction.model.BedrockProperties;
import com.example.extraction.model.ExtractionProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AwsCredentialsStartupListener {

  private final ExtractionProperties extractionProperties;
  private final AwsProperties awsProperties;
  private final BedrockProperties bedrockProperties;

  public AwsCredentialsStartupListener(
      ExtractionProperties extractionProperties,
      AwsProperties awsProperties,
      BedrockProperties bedrockProperties) {
    this.extractionProperties = extractionProperties;
    this.awsProperties = awsProperties;
    this.bedrockProperties = bedrockProperties;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    log.info("========================================");
    log.info("Configuration Check");
    log.info("========================================");

    log.info("AWS Access Key: {}", awsProperties.accessKey() != null ? "✓ Set (length: " + awsProperties.accessKey().length() + ")" : "✗ Not set");
    log.info("AWS Secret Key: {}", awsProperties.secretKey() != null ? "✓ Set (length: " + awsProperties.secretKey().length() + ")" : "✗ Not set");
    log.info("AWS Region: {}", awsProperties.region());

    log.info("========================================");
    log.info("Extraction Configuration");
    log.info("========================================");
    log.info("Bedrock Model ID: {}", bedrockProperties.modelId());
    log.info("S3 Bucket: {}", bedrockProperties.s3Bucket());
    log.info("Max Document Size: {} MB", extractionProperties.maxDocumentSizeBytes() / (1024 * 1024));
    log.info("Max Pages: {}", extractionProperties.maxPages());
    log.info("Max Concurrency: {}", extractionProperties.maxConcurrency());
    log.info("Request Timeout: {}", extractionProperties.requestTimeout());

    log.info("========================================");
    log.info("✓ All configurations loaded successfully");
    log.info("========================================");
  }
}
