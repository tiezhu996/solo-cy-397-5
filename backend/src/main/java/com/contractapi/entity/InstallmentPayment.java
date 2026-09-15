package com.contractapi.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("installment_payments")
public class InstallmentPayment {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long installmentId;
  private LocalDate receivedDate;
  private BigDecimal amount;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getInstallmentId() { return installmentId; }
  public void setInstallmentId(Long installmentId) { this.installmentId = installmentId; }
  public LocalDate getReceivedDate() { return receivedDate; }
  public void setReceivedDate(LocalDate receivedDate) { this.receivedDate = receivedDate; }
  public BigDecimal getAmount() { return amount; }
  public void setAmount(BigDecimal amount) { this.amount = amount; }
}
