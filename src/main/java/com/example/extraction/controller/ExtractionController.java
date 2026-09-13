package com.example.extraction.controller;

import com.example.extraction.exception.ExtractionException;
import com.example.extraction.model.ExtractionResult;
import com.example.extraction.service.ExtractionService;
import com.example.extraction.service.PdfLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/extraction")
public class ExtractionController {

  private final ExtractionService extractionService;
  private final PdfLoader pdfLoader;

  public ExtractionController(ExtractionService extractionService, PdfLoader pdfLoader) {
    this.extractionService = extractionService;
    this.pdfLoader = pdfLoader;
  }

  @PostMapping("/extract-from-s3")
  public ResponseEntity<?> extractFromS3(
      @RequestParam String bucket,
      @RequestParam String key,
      @RequestParam(required = false, defaultValue = "document.pdf") String documentName) {
    try {
      log.info("Extracting PDF from S3: bucket={}, key={}, documentName={}", bucket, key, documentName);

      byte[] pdfBytes = pdfLoader.loadAndValidate(bucket, key, documentName);
      ExtractionResult result = extractionService.extract(pdfBytes, documentName);

      Map<String, Object> response = new HashMap<>();
      response.put("extractedData", result.extractedData());
      response.put("extractionModelId", result.modelId());
      response.put("extractionInputTokens", result.inputTokens());
      response.put("extractionOutputTokens", result.outputTokens());
      response.put("extractionCacheReadTokens", result.cacheReadTokens());
      response.put("capturedScreenshots", result.capturedScreenshots());
      response.put("signaturePageScreenshots", result.signaturePageScreenshots());
      response.put("autoRenewalPageScreenshot", result.autoRenewalPageScreenshot());

      log.info("Extraction completed successfully for document: {}", documentName);
      return ResponseEntity.ok(response);

    } catch (ExtractionException e) {
      log.error("Extraction failed: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(errorResponse(e.getMessage()));
    } catch (Exception e) {
      log.error("Unexpected error during extraction", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(errorResponse("Extraction failed: " + e.getMessage()));
    }
  }

  @PostMapping("/extract-from-file")
  public ResponseEntity<?> extractFromFile(
      @RequestParam("file") MultipartFile file) {
    try {
      if (file.isEmpty()) {
        return ResponseEntity.badRequest().body(errorResponse("File is empty"));
      }

      String documentName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document.pdf";
      log.info("Extracting PDF from file upload: {}", documentName);

      byte[] pdfBytes = file.getBytes();
      ExtractionResult result = extractionService.extract(pdfBytes, documentName);

      Map<String, Object> response = new HashMap<>();
      response.put("extractedData", result.extractedData());
      response.put("extractionModelId", result.modelId());
      response.put("extractionInputTokens", result.inputTokens());
      response.put("extractionOutputTokens", result.outputTokens());
      response.put("extractionCacheReadTokens", result.cacheReadTokens());
      response.put("capturedScreenshots", result.capturedScreenshots());
      response.put("signaturePageScreenshots", result.signaturePageScreenshots());
      response.put("autoRenewalPageScreenshot", result.autoRenewalPageScreenshot());

      log.info("Extraction completed successfully for file: {}", documentName);
      return ResponseEntity.ok(response);

    } catch (ExtractionException e) {
      log.error("Extraction failed: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(errorResponse(e.getMessage()));
    } catch (IOException e) {
      log.error("Failed to read file: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(errorResponse("Failed to read file: " + e.getMessage()));
    } catch (Exception e) {
      log.error("Unexpected error during extraction", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(errorResponse("Extraction failed: " + e.getMessage()));
    }
  }

  private Map<String, Object> errorResponse(String message) {
    Map<String, Object> error = new HashMap<>();
    error.put("error", message);
    error.put("timestamp", System.currentTimeMillis());
    return error;
  }
}
