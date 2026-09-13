package com.example.extraction.exception;

public class ExtractionBpmnException extends ExtractionException {
  private final String errorCode;

  public ExtractionBpmnException(String errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  public String getErrorCode() {
    return errorCode;
  }
}
