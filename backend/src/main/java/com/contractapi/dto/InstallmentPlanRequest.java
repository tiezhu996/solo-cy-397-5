package com.contractapi.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record InstallmentPlanRequest(List<InstallmentItem> items) {
  public record InstallmentItem(Integer periodNo, LocalDate dueDate, BigDecimal amount) {}
}
