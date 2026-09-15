package com.contractapi.dto;

import java.math.BigDecimal;
import java.util.List;

public record PaymentProgressView(Long contractId, BigDecimal contractAmount, BigDecimal totalReceived,
    BigDecimal totalRemaining, int installmentCount, int settledCount, int overdueCount,
    List<InstallmentView> installments) {}
