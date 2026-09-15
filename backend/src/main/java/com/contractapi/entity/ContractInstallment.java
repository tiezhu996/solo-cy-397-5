package com.contractapi.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("contract_installments")
public class ContractInstallment {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long contractId;
  private Integer periodNo;
  private LocalDate dueDate;
  private BigDecimal amount;
  private BigDecimal receivedAmount = BigDecimal.ZERO;
  private String status;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getContractId() { return contractId; }
  public void setContractId(Long contractId) { this.contractId = contractId; }
  public Integer getPeriodNo() { return periodNo; }
  public void setPeriodNo(Integer periodNo) { this.periodNo = periodNo; }
  public LocalDate getDueDate() { return dueDate; }
  public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
  public BigDecimal getAmount() { return amount; }
  public void setAmount(BigDecimal amount) { this.amount = amount; }
  public BigDecimal getReceivedAmount() { return receivedAmount; }
  public void setReceivedAmount(BigDecimal receivedAmount) { this.receivedAmount = receivedAmount; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
}
