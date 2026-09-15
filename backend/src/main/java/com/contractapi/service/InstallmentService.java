package com.contractapi.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.contractapi.constants.ContractStatus;
import com.contractapi.constants.ErrorCode;
import com.contractapi.constants.InstallmentStatus;
import com.contractapi.dto.InstallmentPlanRequest;
import com.contractapi.dto.InstallmentView;
import com.contractapi.dto.OverdueView;
import com.contractapi.dto.PaymentProgressView;
import com.contractapi.dto.PaymentRequest;
import com.contractapi.entity.Contract;
import com.contractapi.entity.ContractInstallment;
import com.contractapi.entity.InstallmentPayment;
import com.contractapi.exception.ApiException;
import com.contractapi.mapper.ContractInstallmentMapper;
import com.contractapi.mapper.ContractMapper;
import com.contractapi.mapper.InstallmentPaymentMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InstallmentService {
  private final ContractMapper contractMapper;
  private final ContractInstallmentMapper installmentMapper;
  private final InstallmentPaymentMapper paymentMapper;

  public InstallmentService(ContractMapper contractMapper, ContractInstallmentMapper installmentMapper,
      InstallmentPaymentMapper paymentMapper) {
    this.contractMapper = contractMapper;
    this.installmentMapper = installmentMapper;
    this.paymentMapper = paymentMapper;
  }

  @Transactional
  public List<InstallmentView> registerPlan(Long contractId, InstallmentPlanRequest request) {
    Contract contract = contractMapper.selectOne(new QueryWrapper<Contract>().eq("id", contractId).last("FOR UPDATE"));
    if (contract == null) {
      throw new ApiException(ErrorCode.NOT_FOUND, "合同不存在: " + contractId);
    }
    if (ContractStatus.DRAFT.name().equals(contract.getStatus()) || ContractStatus.EXPIRED.name().equals(contract.getStatus())) {
      throw new ApiException(ErrorCode.CONTRACT_STATUS_FORBIDDEN, "草稿或已过期状态的合同不允许登记分期");
    }
    if (contract.getAmount() == null) {
      throw new ApiException(ErrorCode.CONTRACT_AMOUNT_MISSING, "合同未设置金额，无法登记分期计划");
    }
    List<InstallmentPlanRequest.InstallmentItem> items = request == null ? null : request.items();
    if (items == null || items.isEmpty()) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, "分期计划不能为空");
    }
    Set<Integer> seenPeriods = new HashSet<>();
    BigDecimal sum = BigDecimal.ZERO;
    List<InstallmentPlanRequest.InstallmentItem> normalizedItems = new ArrayList<>();
    for (InstallmentPlanRequest.InstallmentItem item : items) {
      if (item.periodNo() == null || item.periodNo() <= 0) {
        throw new ApiException(ErrorCode.VALIDATION_FAILED, "期数必须为正整数");
      }
      if (!seenPeriods.add(item.periodNo())) {
        throw new ApiException(ErrorCode.VALIDATION_FAILED, "期数重复: " + item.periodNo());
      }
      if (item.dueDate() == null) {
        throw new ApiException(ErrorCode.VALIDATION_FAILED, "第 " + item.periodNo() + " 期应收日期不能为空");
      }
      if (item.amount() == null) {
        throw new ApiException(ErrorCode.VALIDATION_FAILED, "第 " + item.periodNo() + " 期金额不能为空");
      }
      // 金额统一归一到分（两位小数），与 DECIMAL(15,2) 存储精度一致，避免入账后账目对不上
      BigDecimal amount = item.amount().setScale(2, RoundingMode.HALF_UP);
      if (amount.signum() <= 0) {
        throw new ApiException(ErrorCode.VALIDATION_FAILED, "第 " + item.periodNo() + " 期金额必须大于 0");
      }
      sum = sum.add(amount);
      normalizedItems.add(new InstallmentPlanRequest.InstallmentItem(item.periodNo(), item.dueDate(), amount));
    }
    if (sum.compareTo(contract.getAmount()) != 0) {
      throw new ApiException(ErrorCode.AMOUNT_MISMATCH,
          "分期金额合计 " + sum.toPlainString() + " 与合同金额 " + contract.getAmount().toPlainString() + " 不一致");
    }
    Long existing = installmentMapper.selectCount(new QueryWrapper<ContractInstallment>().eq("contract_id", contractId));
    if (existing != null && existing > 0) {
      throw new ApiException(ErrorCode.PLAN_ALREADY_EXISTS, "该合同已存在分期计划，不允许重复登记");
    }
    List<InstallmentPlanRequest.InstallmentItem> sorted = normalizedItems.stream()
        .sorted(Comparator.comparing(InstallmentPlanRequest.InstallmentItem::periodNo)).toList();
    for (InstallmentPlanRequest.InstallmentItem item : sorted) {
      ContractInstallment installment = new ContractInstallment();
      installment.setContractId(contractId);
      installment.setPeriodNo(item.periodNo());
      installment.setDueDate(item.dueDate());
      installment.setAmount(item.amount());
      installment.setReceivedAmount(BigDecimal.ZERO);
      installment.setStatus(InstallmentStatus.PENDING.name());
      installmentMapper.insert(installment);
    }
    return listInstallments(contractId);
  }

  @Transactional
  public InstallmentView recordPayment(Long contractId, Long installmentId, PaymentRequest request) {
    ContractInstallment installment = installmentMapper.selectOne(
        new QueryWrapper<ContractInstallment>().eq("id", installmentId).last("FOR UPDATE"));
    if (installment == null || !installment.getContractId().equals(contractId)) {
      throw new ApiException(ErrorCode.NOT_FOUND, "分期不存在: " + installmentId);
    }
    if (request == null || request.amount() == null) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, "收款金额不能为空");
    }
    if (request.receivedDate() == null) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, "实收日期不能为空");
    }
    // 先归一到分（两位小数）再做校验与累加，保证计算值与 DECIMAL(15,2) 存储值一致
    BigDecimal amount = request.amount().setScale(2, RoundingMode.HALF_UP);
    if (amount.signum() <= 0) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, "收款金额必须大于 0");
    }
    BigDecimal received = installment.getReceivedAmount() == null ? BigDecimal.ZERO : installment.getReceivedAmount();
    BigDecimal remaining = installment.getAmount().subtract(received);
    if (amount.compareTo(remaining) > 0) {
      throw new ApiException(ErrorCode.PAYMENT_EXCEEDS_DUE,
          "第 " + installment.getPeriodNo() + " 期剩余应收 " + remaining.toPlainString()
              + "，本次收款 " + amount.toPlainString() + " 超出应收金额");
    }
    InstallmentPayment payment = new InstallmentPayment();
    payment.setInstallmentId(installmentId);
    payment.setReceivedDate(request.receivedDate());
    payment.setAmount(amount);
    paymentMapper.insert(payment);

    BigDecimal newReceived = received.add(amount);
    installment.setReceivedAmount(newReceived);
    installment.setStatus(newReceived.compareTo(installment.getAmount()) >= 0
        ? InstallmentStatus.SETTLED.name() : InstallmentStatus.PARTIAL.name());
    installmentMapper.updateById(installment);
    return toView(installment);
  }

  public List<InstallmentView> listInstallments(Long contractId) {
    requireContract(contractId);
    return listByContract(contractId).stream().map(this::toView).toList();
  }

  public PaymentProgressView getProgress(Long contractId) {
    Contract contract = requireContract(contractId);
    List<InstallmentView> views = listByContract(contractId).stream().map(this::toView).toList();
    BigDecimal totalReceived = BigDecimal.ZERO;
    BigDecimal totalRemaining = BigDecimal.ZERO;
    int settled = 0;
    int overdue = 0;
    for (InstallmentView view : views) {
      totalReceived = totalReceived.add(view.receivedAmount());
      totalRemaining = totalRemaining.add(view.remainingAmount());
      if (InstallmentStatus.SETTLED.name().equals(view.status())) {
        settled++;
      }
      if (view.overdue()) {
        overdue++;
      }
    }
    return new PaymentProgressView(contractId, contract.getAmount(), totalReceived, totalRemaining,
        views.size(), settled, overdue, views);
  }

  public OverdueView getOverdue(Long contractId) {
    requireContract(contractId);
    List<InstallmentView> overdueViews = listByContract(contractId).stream()
        .map(this::toView).filter(InstallmentView::overdue).toList();
    BigDecimal overdueAmount = BigDecimal.ZERO;
    for (InstallmentView view : overdueViews) {
      overdueAmount = overdueAmount.add(view.remainingAmount());
    }
    return new OverdueView(contractId, overdueViews.size(), overdueAmount, overdueViews);
  }

  private Contract requireContract(Long contractId) {
    Contract contract = contractMapper.selectById(contractId);
    if (contract == null) {
      throw new ApiException(ErrorCode.NOT_FOUND, "合同不存在: " + contractId);
    }
    return contract;
  }

  private List<ContractInstallment> listByContract(Long contractId) {
    return installmentMapper.selectList(new QueryWrapper<ContractInstallment>()
        .eq("contract_id", contractId).orderByAsc("period_no"));
  }

  private InstallmentView toView(ContractInstallment installment) {
    BigDecimal received = installment.getReceivedAmount() == null ? BigDecimal.ZERO : installment.getReceivedAmount();
    BigDecimal remaining = installment.getAmount().subtract(received);
    boolean overdue = !InstallmentStatus.SETTLED.name().equals(installment.getStatus())
        && installment.getDueDate().isBefore(LocalDate.now());
    return new InstallmentView(installment.getId(), installment.getPeriodNo(), installment.getDueDate(),
        installment.getAmount(), received, remaining, installment.getStatus(), overdue);
  }
}
