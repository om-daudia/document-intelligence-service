package com.example.extraction;

import com.example.extraction.model.ExtractionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ExtractionProperties.class)
public class BedrockExtractionApplication {

  public static void main(String[] args) {
    SpringApplication.run(BedrockExtractionApplication.class, args);
  }
}
