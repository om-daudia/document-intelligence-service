package com.example.extraction.service;

import com.example.extraction.exception.ExtractionBpmnException;
import com.example.extraction.model.ExtractionProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

@Slf4j
@Component
public class PdfLoader {

  private final S3Client s3Client;
  private final ExtractionProperties config;

  public PdfLoader(S3Client s3Client, ExtractionProperties config) {
    this.s3Client = s3Client;
    this.config = config;
  }

  public byte[] loadAndValidate(String bucket, String key, String documentName) throws ExtractionBpmnException {
    try {
      log.info("Fetching PDF from S3: bucket='{}', key='{}', documentName='{}'", bucket, key, documentName);

      GetObjectRequest request = GetObjectRequest.builder()
          .bucket(bucket)
          .key(key)
          .build();

      byte[] pdfBytes = s3Client.getObjectAsBytes(request).asByteArray();

      // Validate size
      if (pdfBytes.length > config.maxDocumentSizeBytes()) {
        throw new ExtractionBpmnException(
            "EXTRACTION_INVALID_DOCUMENT",
            String.format("Document '%s' exceeds max size: %d bytes > %d bytes",
                documentName, pdfBytes.length, config.maxDocumentSizeBytes()));
      }

      log.info("Document validated: name='{}', size={} bytes", documentName, pdfBytes.length);

      return pdfBytes;

    } catch (NoSuchKeyException e) {
      throw new ExtractionBpmnException(
          "EXTRACTION_INVALID_DOCUMENT",
          String.format("Document '%s' not found in S3: %s", documentName, e.getMessage()));
    } catch (ExtractionBpmnException e) {
      throw e;
    } catch (Exception e) {
      throw new ExtractionBpmnException(
          "EXTRACTION_INVALID_DOCUMENT",
          String.format("Failed to load document '%s' from S3: %s", documentName, e.getMessage()));
    }
  }

}
