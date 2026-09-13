package com.example.extraction.exception;

public class ExtractionRetryableException extends ExtractionException {
  public ExtractionRetryableException(Throwable cause) {
    super("Transient extraction error; eligible for retry", cause);
  }
}
