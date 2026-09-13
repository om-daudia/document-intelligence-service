package com.example.extraction.config;

import com.example.extraction.model.AwsProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

import java.time.Duration;

@Configuration
public class BedrockConfig {
    @Bean
    @ConditionalOnMissingBean
    public BedrockRuntimeClient bedrockRuntimeClient(AwsProperties awsProps) {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(
                awsProps.accessKey(),
                awsProps.secretKey()
        );

        return BedrockRuntimeClient.builder()
                .region(Region.of(awsProps.region()))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .overrideConfiguration(config -> config
                        .retryPolicy(RetryPolicy.builder().build())
                        .apiCallAttemptTimeout(Duration.ofSeconds(30))
                        .apiCallTimeout(Duration.ofMinutes(5)))
                .build();
    }
}
