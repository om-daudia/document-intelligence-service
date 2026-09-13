package com.example.extraction.model;

import java.util.List;
import java.util.Map;

public record ExtractionResult(
    Map<String, Object> extractedData,
    String modelId,
    Integer inputTokens,
    Integer outputTokens,
    Integer cacheReadTokens,
    List<String> capturedScreenshots,
    List<String> signaturePageScreenshots,
    String autoRenewalPageScreenshot
) {}
