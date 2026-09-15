package com.contractapi.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PaymentRequest(LocalDate receivedDate, BigDecimal amount) {}
