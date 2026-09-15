package com.contractapi.constants;

public final class ErrorCode {
  public static final String NOT_FOUND = "NOT_FOUND";
  public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
  public static final String PDF_EXPORT_FAILED = "PDF_EXPORT_FAILED";
  public static final String CONTRACT_STATUS_FORBIDDEN = "CONTRACT_STATUS_FORBIDDEN";
  public static final String CONTRACT_AMOUNT_MISSING = "CONTRACT_AMOUNT_MISSING";
  public static final String AMOUNT_MISMATCH = "AMOUNT_MISMATCH";
  public static final String PAYMENT_EXCEEDS_DUE = "PAYMENT_EXCEEDS_DUE";
  public static final String PLAN_ALREADY_EXISTS = "PLAN_ALREADY_EXISTS";
  private ErrorCode() {}
}
