package com.contractapi.controller;

import java.util.List;
import com.contractapi.dto.InstallmentPlanRequest;
import com.contractapi.dto.InstallmentView;
import com.contractapi.dto.OverdueView;
import com.contractapi.dto.PaymentProgressView;
import com.contractapi.dto.PaymentRequest;
import com.contractapi.service.InstallmentService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/contracts/{contractId}/installments")
public class InstallmentController {
  private final InstallmentService service;
  public InstallmentController(InstallmentService service) { this.service = service; }

  @PostMapping
  public List<InstallmentView> registerPlan(@PathVariable Long contractId, @RequestBody InstallmentPlanRequest request) {
    return service.registerPlan(contractId, request);
  }

  @GetMapping
  public List<InstallmentView> list(@PathVariable Long contractId) {
    return service.listInstallments(contractId);
  }

  @PostMapping("/{installmentId}/payments")
  public InstallmentView recordPayment(@PathVariable Long contractId, @PathVariable Long installmentId,
      @RequestBody PaymentRequest request) {
    return service.recordPayment(contractId, installmentId, request);
  }

  @GetMapping("/progress")
  public PaymentProgressView progress(@PathVariable Long contractId) {
    return service.getProgress(contractId);
  }

  @GetMapping("/overdue")
  public OverdueView overdue(@PathVariable Long contractId) {
    return service.getOverdue(contractId);
  }
}
