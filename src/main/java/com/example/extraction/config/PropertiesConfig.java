package com.example.extraction.config;

import com.example.extraction.model.AwsProperties;
import com.example.extraction.model.BedrockProperties;
import com.example.extraction.model.ExtractionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({AwsProperties.class, BedrockProperties.class, ExtractionProperties.class})
public class PropertiesConfig {
}
