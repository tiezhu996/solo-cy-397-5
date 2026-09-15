package com.contractapi.dto;

import java.math.BigDecimal;
import java.util.Map;

public record GenerateContractRequest(Long userId, Long templateId, String title, BigDecimal amount, Map<String, String> variables, String format) {}
