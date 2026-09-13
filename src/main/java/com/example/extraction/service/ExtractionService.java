package com.example.extraction.service;

import com.example.extraction.exception.ExtractionBpmnException;
import com.example.extraction.exception.ExtractionException;
import com.example.extraction.exception.ExtractionRetryableException;
import com.example.extraction.model.BedrockProperties;
import com.example.extraction.model.ExtractionProperties;
import com.example.extraction.model.ExtractionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;
import software.amazon.awssdk.services.bedrockruntime.model.ValidationException;
import software.amazon.awssdk.core.SdkBytes;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Extraction service using Bedrock with prompt caching via system prompt.
 *
 * The extraction prompt is large and identical across all documents, making it
 * an ideal candidate for caching. The prompt is included in every request,
 * allowing Bedrock to cache it automatically after the first invocation.
 */
@Slf4j
@Service
public class ExtractionService {

  private static final String EXTRACTION_PROMPT = """
    Analyze the attached contract PDF. Check the page visuals too signatures may be images or handwriting.
    
    A line is signed only if a name or mark sits on it; a blank line, a date alone, or a name printed elsewhere doesn't count. Report signed lines only.
    
    Return only this JSON:
    {
      "hasMonthlySubscriptionOrRecurringCharge": <true|false>,
      "hasAutoRenewal": <true|false>,
      "autoRenewalPageNumber": <int|null>,
      "isSignUploaded": <true|false>,
      "signPageNumber": [<int>, ...],
      "signedInfo": [
        {"page": <int>, "section": "<nearest heading>", "label": "<label>", "value": "<name found>"}
      ]
    }
    """;

  private final BedrockRuntimeClient bedrockClient;
  private final Semaphore concurrencySemaphore;
  private final ExtractionProperties extractionConfig;
  private final BedrockProperties bedrockConfig;
  private final MeterRegistry meterRegistry;
  private final ObjectMapper objectMapper;
  private final PdfScreenshotService pdfScreenshotService;

  private Counter documentsProcessed;
  private Counter documentsFailed;
  private Counter cacheHits;

  public ExtractionService(
      BedrockRuntimeClient bedrockClient,
      ExtractionProperties extractionConfig,
      BedrockProperties bedrockConfig,
      MeterRegistry meterRegistry,
      PdfScreenshotService pdfScreenshotService) {
    this.bedrockClient = bedrockClient;
    this.extractionConfig = extractionConfig;
    this.bedrockConfig = bedrockConfig;
    this.meterRegistry = meterRegistry;
    this.pdfScreenshotService = pdfScreenshotService;
    this.concurrencySemaphore = new Semaphore(extractionConfig.maxConcurrency());
    this.objectMapper = new ObjectMapper();

    this.documentsProcessed = Counter.builder("extraction.documents.processed")
        .description("Total documents processed")
        .register(meterRegistry);
    this.documentsFailed = Counter.builder("extraction.documents.failed")
        .description("Total documents failed")
        .register(meterRegistry);
    this.cacheHits = Counter.builder("extraction.cache.hits")
        .description("Bedrock cache hits")
        .register(meterRegistry);
  }

  public ExtractionResult extract(byte[] pdfBytes, String documentName) throws ExtractionException {
    try {
      boolean acquired = concurrencySemaphore.tryAcquire(extractionConfig.requestTimeout().toSeconds(), TimeUnit.SECONDS);
    if (!acquired) {
      throw new ExtractionException("Concurrency limit exceeded, request timed out");
    }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ExtractionException("Thread interrupted while waiting for semaphore", e);
    }

    try {
      String sanitizedName = sanitizeDocumentName(documentName);
      log.info("Starting extraction for document: original='{}', sanitized='{}'", documentName, sanitizedName);

      String requestBody = buildRequestBody(pdfBytes, sanitizedName);

      InvokeModelRequest request = InvokeModelRequest.builder()
          .modelId(bedrockConfig.modelId())
          .contentType("application/json")
          .accept("application/json")
          .body(SdkBytes.fromString(requestBody, StandardCharsets.UTF_8))
          .build();

      InvokeModelResponse response = bedrockClient.invokeModel(request);
      String responseBody = response.body().asUtf8String();

      log.info("Extraction response received for document '{}'", documentName);

      ExtractionResult result = parseResponse(responseBody, documentName, pdfBytes);
      documentsProcessed.increment();
      return result;

    } catch (software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException e) {
      documentsFailed.increment();
      log.warn("Bedrock throttling for document '{}': {}", documentName, e.getMessage());
      throw new ExtractionRetryableException(e);
    } catch (software.amazon.awssdk.services.bedrockruntime.model.ValidationException e) {
      documentsFailed.increment();
      log.error("Bedrock validation error for document '{}': {}", documentName, e.getMessage());
      throw new ExtractionBpmnException("EXTRACTION_INVALID_DOCUMENT", e.getMessage());
    } catch (ExtractionException e) {
      documentsFailed.increment();
      throw e;
    } catch (Exception e) {
      documentsFailed.increment();
      log.error("Extraction failed for document '{}': {}", documentName, e.getMessage(), e);
      throw new ExtractionException("Unexpected error during extraction: " + e.getMessage(), e);
    } finally {
      concurrencySemaphore.release();
    }
  }

  private String buildRequestBody(byte[] pdfBytes, String documentName) throws Exception {
    String encodedPdf = Base64.getEncoder().encodeToString(pdfBytes);

    Map<String, Object> systemContent = new HashMap<>();
    systemContent.put("type", "text");
    systemContent.put("text", EXTRACTION_PROMPT);

    Map<String, Object> documentContent = new HashMap<>();
    documentContent.put("type", "document");
    Map<String, Object> source = new HashMap<>();
    source.put("type", "base64");
    source.put("media_type", "application/pdf");
    source.put("data", encodedPdf);
    documentContent.put("source", source);
    documentContent.put("title", documentName);

    Map<String, Object> textContent = new HashMap<>();
    textContent.put("type", "text");
    textContent.put("text", "Extract the structured data from this document and return it as JSON matching the schema.");

    Map<String, Object> request = new HashMap<>();
    request.put("anthropic_version", "bedrock-2023-05-31");
    request.put("max_tokens", 4096);
    request.put("system", EXTRACTION_PROMPT);
    request.put("messages", List.of(
        Map.of(
            "role", "user",
            "content", List.of(documentContent, textContent)
        )
    ));

    return objectMapper.writeValueAsString(request);
  }

  private ExtractionResult parseResponse(String responseBody, String documentName, byte[] pdfBytes) throws ExtractionBpmnException, Exception {
    JsonNode responseJson = objectMapper.readTree(responseBody);

    if (responseJson.has("error")) {
      String errorMessage = responseJson.get("error").get("message").asText();
      throw new ExtractionBpmnException("EXTRACTION_FAILED", errorMessage);
    }

    JsonNode content = responseJson.get("content");
    if (content == null || content.size() == 0) {
      throw new ExtractionBpmnException("EXTRACTION_FAILED",
          "No content in response for document '" + documentName + "'");
    }

    JsonNode textBlock = null;
    for (JsonNode block : content) {
      if ("text".equals(block.get("type").asText())) {
        textBlock = block;
        break;
      }
    }

    if (textBlock == null) {
      throw new ExtractionBpmnException("EXTRACTION_FAILED",
          "Model did not return text content for document '" + documentName + "'");
    }

    String text = textBlock.get("text").asText();

    // Extract JSON from response (it might be wrapped in markdown code blocks)
    String jsonText = text;
    if (jsonText.contains("```json")) {
      jsonText = jsonText.substring(jsonText.indexOf("```json") + 7);
      jsonText = jsonText.substring(0, jsonText.indexOf("```"));
    } else if (jsonText.contains("```")) {
      jsonText = jsonText.substring(jsonText.indexOf("```") + 3);
      jsonText = jsonText.substring(0, jsonText.indexOf("```"));
    }
    jsonText = jsonText.trim();

    Map<String, Object> extractedData = objectMapper.readValue(jsonText, Map.class);

    Integer inputTokens = responseJson.has("usage") ? responseJson.get("usage").get("input_tokens").asInt() : 0;
    Integer outputTokens = responseJson.has("usage") ? responseJson.get("usage").get("output_tokens").asInt() : 0;

    log.info("Extraction complete: inputTokens={}, outputTokens={}", inputTokens, outputTokens);

    Map<String, Object> captureResult = pdfScreenshotService.captureAllPages(pdfBytes, documentName, extractedData);

    @SuppressWarnings("unchecked")
    List<String> signatureScreenshots = (List<String>) captureResult.get("signaturePageScreenshots");
    String autoRenewalScreenshot = (String) captureResult.get("autoRenewalPageScreenshot");

    List<String> allCapturedScreenshots = new ArrayList<>(signatureScreenshots);
    if (autoRenewalScreenshot != null) {
      allCapturedScreenshots.add(autoRenewalScreenshot);
    }

    return new ExtractionResult(
        extractedData,
        bedrockConfig.modelId(),
        inputTokens,
        outputTokens,
        0,
        allCapturedScreenshots,
        signatureScreenshots,
        autoRenewalScreenshot);
  }

  private String sanitizeDocumentName(String original) {
    // Bedrock accepts: [a-zA-Z0-9\s\-()[\]]
    return original
        .replaceAll("[^a-zA-Z0-9\\s\\-()\\[\\]]", "")
        .replaceAll("\\s+", " ")
        .trim();
  }
}
