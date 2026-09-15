package com.contractapi.dto;

import java.math.BigDecimal;
import java.util.List;

public record OverdueView(Long contractId, int overdueCount, BigDecimal overdueAmount,
    List<InstallmentView> overdueInstallments) {}
