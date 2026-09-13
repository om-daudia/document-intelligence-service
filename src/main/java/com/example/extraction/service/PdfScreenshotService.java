package com.example.extraction.service;

import com.example.extraction.exception.ExtractionException;
import com.example.extraction.model.ExtractionProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class PdfScreenshotService {

  private final ExtractionProperties extractionConfig;

  public PdfScreenshotService(ExtractionProperties extractionConfig) {
    this.extractionConfig = extractionConfig;
  }

  public Map<String, Object> captureAllPages(
      byte[] pdfBytes,
      String documentName,
      Map<String, Object> extractedData) {

    Map<String, Object> result = new HashMap<>();
    result.put("signaturePageScreenshots", new ArrayList<String>());
    result.put("autoRenewalPageScreenshot", null);

    try {
      String outputDir = ensureOutputDirectory();
      String pdfFileName = sanitizeFileName(documentName);
      File pdfTempFile = savePdfToTemp(pdfBytes, pdfFileName);

      captureSignaturePagesInternal(pdfTempFile, documentName, pdfFileName, outputDir, extractedData, result);
      captureAutoRenewalPageInternal(pdfTempFile, documentName, pdfFileName, outputDir, extractedData, result);

      pdfTempFile.delete();

      @SuppressWarnings("unchecked")
      List<String> signatureScreenshots = (List<String>) result.get("signaturePageScreenshots");
      String autoRenewalScreenshot = (String) result.get("autoRenewalPageScreenshot");

      int totalCaptured = signatureScreenshots.size() + (autoRenewalScreenshot != null ? 1 : 0);
      if (totalCaptured == 0) {
        log.warn("No pages were successfully captured for document: {}", documentName);
      } else {
        log.info("Successfully captured {} pages for document: {}", totalCaptured, documentName);
      }

    } catch (Exception e) {
      log.error("Error capturing pages for document {}: {}", documentName, e.getMessage(), e);
    }

    return result;
  }

  @SuppressWarnings("unchecked")
  private void captureSignaturePagesInternal(
      File pdfTempFile,
      String documentName,
      String pdfFileName,
      String outputDir,
      Map<String, Object> extractedData,
      Map<String, Object> result) {

    List<Integer> signPageNumbers = (List<Integer>) extractedData.get("signPageNumber");

    if (signPageNumbers == null || signPageNumbers.isEmpty()) {
      log.info("No signature pages found for document: {}", documentName);
      return;
    }

    log.info("Capturing {} signature pages for document: {}", signPageNumbers.size(), documentName);
    List<String> capturedFiles = (List<String>) result.get("signaturePageScreenshots");

    for (Integer pageNum : signPageNumbers) {
      try {
        String outputPath = capturePageAsJpeg(pdfTempFile, pageNum, pdfFileName, "signature", outputDir);
        capturedFiles.add(outputPath);
        log.info("Captured signature page {} for document {}: {}", pageNum, documentName, outputPath);
      } catch (Exception e) {
        log.error("Failed to capture signature page {} for document {}", pageNum, documentName, e);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private void captureAutoRenewalPageInternal(
      File pdfTempFile,
      String documentName,
      String pdfFileName,
      String outputDir,
      Map<String, Object> extractedData,
      Map<String, Object> result) {

    Integer autoRenewalPageNumber = null;
    Object autoRenewalObj = extractedData.get("autoRenewalPageNumber");

    if (autoRenewalObj instanceof Integer) {
      autoRenewalPageNumber = (Integer) autoRenewalObj;
    } else if (autoRenewalObj instanceof Double) {
      autoRenewalPageNumber = ((Double) autoRenewalObj).intValue();
    }

    if (autoRenewalPageNumber == null) {
      log.info("No auto-renewal page found for document: {}", documentName);
      return;
    }

    log.info("Capturing auto-renewal page {} for document: {}", autoRenewalPageNumber, documentName);

    try {
      String outputPath = capturePageAsJpeg(pdfTempFile, autoRenewalPageNumber, pdfFileName, "autorenewal", outputDir);
      result.put("autoRenewalPageScreenshot", outputPath);
      log.info("Captured auto-renewal page {} for document {}: {}", autoRenewalPageNumber, documentName, outputPath);
    } catch (Exception e) {
      log.error("Failed to capture auto-renewal page {} for document {}", autoRenewalPageNumber, documentName, e);
    }
  }

  public List<String> captureSignaturePages(
      byte[] pdfBytes,
      String documentName,
      Map<String, Object> extractedData) {

    Map<String, Object> captureResult = captureAllPages(pdfBytes, documentName, extractedData);

    @SuppressWarnings("unchecked")
    List<String> signatureScreenshots = (List<String>) captureResult.get("signaturePageScreenshots");
    String autoRenewalScreenshot = (String) captureResult.get("autoRenewalPageScreenshot");

    List<String> allScreenshots = new ArrayList<>(signatureScreenshots);
    if (autoRenewalScreenshot != null) {
      allScreenshots.add(autoRenewalScreenshot);
    }

    return allScreenshots;
  }

  private String ensureOutputDirectory() throws IOException {
    String outputDir = extractionConfig.screenshotOutputDir();
    Path dirPath = Paths.get(outputDir);

    if (!Files.exists(dirPath)) {
      Files.createDirectories(dirPath);
      log.info("Created screenshot output directory: {}", outputDir);
    }

    return outputDir;
  }

  private File savePdfToTemp(byte[] pdfBytes, String documentName) throws IOException {
    String tempDir = System.getProperty("java.io.tmpdir");
    File tempFile = new File(tempDir, "bedrock_" + System.currentTimeMillis() + "_" + documentName);
    Files.write(tempFile.toPath(), pdfBytes);
    log.debug("Saved PDF to temp file: {}", tempFile.getAbsolutePath());
    return tempFile;
  }

  private String capturePageAsJpeg(
      File pdfFile,
      Integer pageNum,
      String documentName,
      String pageType,
      String outputDir) throws ExtractionException {

    String outputFileName = String.format(
        "%s_%s_page_%d",
        sanitizeFileName(documentName),
        pageType,
        pageNum
    );

    String outputPrefix = Paths.get(outputDir, outputFileName).toString();
    String outputPath = outputPrefix + ".jpg";

    try {
      ProcessBuilder pb = new ProcessBuilder(
          "pdftoppm",
          "-f", pageNum.toString(),
          "-l", pageNum.toString(),
          "-r", "300",
          "-jpeg",
          "-singlefile",
          pdfFile.getAbsolutePath(),
          outputPrefix
      );

      log.debug("Running pdftoppm command: {}", String.join(" ", pb.command()));
      log.debug("Expected output file: {}", outputPath);

      Process process = pb.start();

      String errorOutput = new String(process.getErrorStream().readAllBytes());
      String standardOutput = new String(process.getInputStream().readAllBytes());
      int exitCode = process.waitFor();

      if (!errorOutput.isEmpty()) {
        log.debug("pdftoppm stderr: {}", errorOutput);
      }
      if (!standardOutput.isEmpty()) {
        log.debug("pdftoppm stdout: {}", standardOutput);
      }

      if (exitCode != 0) {
        throw new ExtractionException(
            String.format(
                "pdftoppm failed with exit code %d for page %d. Error: %s",
                exitCode,
                pageNum,
                errorOutput.isEmpty() ? "No error message" : errorOutput
            )
        );
      }

      if (!Files.exists(Paths.get(outputPath))) {
        log.error("Expected file not found at: {}", outputPath);
        log.error("Looking for files in: {}", outputDir);
        File dir = new File(outputDir);
        if (dir.exists()) {
          File[] files = dir.listFiles();
          if (files != null) {
            log.error("Files in directory: {}", java.util.Arrays.toString(files));
          }
        }
        throw new ExtractionException(
            String.format("Screenshot file not created at: %s (exitCode: %d)", outputPath, exitCode)
        );
      }

      return outputPath;

    } catch (IOException e) {
      log.error("IOException executing pdftoppm for page {}: {}", pageNum, e.getMessage(), e);
      throw new ExtractionException(
          String.format("Failed to execute pdftoppm for page %d: %s", pageNum, e.getMessage()),
          e
      );
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ExtractionException(
          String.format("pdftoppm process interrupted for page %d", pageNum),
          e
      );
    }
  }

  private String sanitizeFileName(String fileName) {
    return fileName
        .replaceAll("[^a-zA-Z0-9._-]", "_")
        .replaceAll("_+", "_")
        .replaceFirst("\\.pdf$", "")
        .toLowerCase();
  }
}
