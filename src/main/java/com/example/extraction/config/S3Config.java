package com.example.extraction.config;

import com.example.extraction.model.AwsProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class S3Config {
  @Bean
  @ConditionalOnMissingBean
  public S3Client s3Client(AwsProperties awsProps) {
      AwsBasicCredentials credentials =
              AwsBasicCredentials.create(awsProps.accessKey(), awsProps.secretKey());

      return S3Client.builder()
              .region(Region.of(awsProps.region()))
              .credentialsProvider(StaticCredentialsProvider.create(credentials))
              .build();
  }
}
