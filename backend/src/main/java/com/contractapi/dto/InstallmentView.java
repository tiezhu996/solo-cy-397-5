package com.contractapi.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InstallmentView(Long id, Integer periodNo, LocalDate dueDate, BigDecimal amount,
    BigDecimal receivedAmount, BigDecimal remainingAmount, String status, boolean overdue) {}
