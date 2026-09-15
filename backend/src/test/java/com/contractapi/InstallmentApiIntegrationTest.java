package com.contractapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.contractapi.constants.ContractStatus;
import com.contractapi.entity.Contract;
import com.contractapi.entity.ContractInstallment;
import com.contractapi.entity.InstallmentPayment;
import com.contractapi.mapper.ContractInstallmentMapper;
import com.contractapi.mapper.ContractMapper;
import com.contractapi.mapper.InstallmentPaymentMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 合同分期收款模块集成测试。
 * 通过 Testcontainers 启动独立的 MySQL 8.0，每个用例自建数据并在结束后清理，
 * 不依赖固定 ID 与固定日期，可连续重复运行。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class InstallmentApiIntegrationTest {

  @Container
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"));

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private ContractMapper contractMapper;
  @Autowired private ContractInstallmentMapper installmentMapper;
  @Autowired private InstallmentPaymentMapper paymentMapper;

  private final List<Long> createdContractIds = new ArrayList<>();

  @AfterEach
  void cleanup() {
    for (Long contractId : createdContractIds) {
      List<Long> installmentIds = installmentMapper.selectList(
          new QueryWrapper<ContractInstallment>().eq("contract_id", contractId))
          .stream().map(ContractInstallment::getId).toList();
      if (!installmentIds.isEmpty()) {
        paymentMapper.delete(new QueryWrapper<InstallmentPayment>().in("installment_id", installmentIds));
      }
      installmentMapper.delete(new QueryWrapper<ContractInstallment>().eq("contract_id", contractId));
      contractMapper.deleteById(contractId);
    }
    createdContractIds.clear();
  }

  // ---------- 正常路径 ----------

  @Test
  void registerPlanAndRecordPayments() throws Exception {
    long contractId = newContract(ContractStatus.SIGNED, "1000.00");

    JsonNode plan = registerPlanOk(contractId, List.of(
        item(1, LocalDate.now().plusDays(10), "200.00"),
        item(2, LocalDate.now().plusDays(20), "300.00"),
        item(3, LocalDate.now().plusDays(30), "500.00")));
    assertEquals(3, plan.size());
    assertEquals("PENDING", plan.get(0).get("status").asText());
    assertMoney(plan.get(0), "receivedAmount", "0.00");
    long inst1 = plan.get(0).get("id").asLong();
    long inst2 = plan.get(1).get("id").asLong();

    // 第 1 期一次收满，第 2 期分两次逐期收款
    JsonNode pay1 = payOk(contractId, inst1, "200.00");
    assertEquals("SETTLED", pay1.get("status").asText());
    assertMoney(pay1, "remainingAmount", "0.00");

    JsonNode pay2a = payOk(contractId, inst2, "100.00");
    assertEquals("PARTIAL", pay2a.get("status").asText());
    assertMoney(pay2a, "remainingAmount", "200.00");

    JsonNode pay2b = payOk(contractId, inst2, "200.00");
    assertEquals("SETTLED", pay2b.get("status").asText());
    assertMoney(pay2b, "remainingAmount", "0.00");

    JsonNode progress = getJson("/api/contracts/{cid}/installments/progress", contractId);
    assertMoney(progress, "contractAmount", "1000.00");
    assertMoney(progress, "totalReceived", "500.00");
    assertMoney(progress, "totalRemaining", "500.00");
    assertEquals(3, progress.get("installmentCount").asInt());
    assertEquals(2, progress.get("settledCount").asInt());
    assertEquals(0, progress.get("overdueCount").asInt());

    // 数据真实落库，可重复读回
    JsonNode installments = getJson("/api/contracts/{cid}/installments", contractId);
    assertEquals(3, installments.size());
    assertMoney(installments.get(0), "receivedAmount", "200.00");
    assertMoney(installments.get(1), "receivedAmount", "300.00");
    assertMoney(installments.get(2), "receivedAmount", "0.00");
  }

  // ---------- 边界路径 ----------

  @Test
  void registerPlanAmountMismatchFails() throws Exception {
    long contractId = newContract(ContractStatus.SIGNED, "1000.00");

    postPlan(contractId, List.of(
        item(1, LocalDate.now().plusDays(10), "400.00"),
        item(2, LocalDate.now().plusDays(20), "500.00")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("AMOUNT_MISMATCH"));

    // 校验失败不落库
    JsonNode installments = getJson("/api/contracts/{cid}/installments", contractId);
    assertEquals(0, installments.size());
  }

  @Test
  void registerPlanDuplicatePeriodFails() throws Exception {
    long contractId = newContract(ContractStatus.SIGNED, "1000.00");

    // 合计与合同金额一致，但期数重复，仍应失败
    postPlan(contractId, List.of(
        item(1, LocalDate.now().plusDays(10), "500.00"),
        item(1, LocalDate.now().plusDays(20), "500.00")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    JsonNode installments = getJson("/api/contracts/{cid}/installments", contractId);
    assertEquals(0, installments.size());
  }

  @Test
  void recordPaymentExceedsDueFails() throws Exception {
    long contractId = newContract(ContractStatus.SIGNED, "100.00");
    JsonNode plan = registerPlanOk(contractId, List.of(item(1, LocalDate.now().plusDays(5), "100.00")));
    long installmentId = plan.get(0).get("id").asLong();

    payOk(contractId, installmentId, "60.00");

    // 剩余 40，再收 50 超出应收
    postPayment(contractId, installmentId, "50.00")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("PAYMENT_EXCEEDS_DUE"));

    // 失败的收款未入账
    JsonNode installments = getJson("/api/contracts/{cid}/installments", contractId);
    assertMoney(installments.get(0), "receivedAmount", "60.00");
    assertEquals("PARTIAL", installments.get(0).get("status").asText());
  }

  @Test
  void registerPlanDraftOrExpiredRejected() throws Exception {
    long draft = newContract(ContractStatus.DRAFT, "100.00");
    postPlan(draft, List.of(item(1, LocalDate.now().plusDays(5), "100.00")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("CONTRACT_STATUS_FORBIDDEN"));

    long expired = newContract(ContractStatus.EXPIRED, "100.00");
    postPlan(expired, List.of(item(1, LocalDate.now().plusDays(5), "100.00")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("CONTRACT_STATUS_FORBIDDEN"));

    // 待签署合同允许登记
    long pendingSign = newContract(ContractStatus.PENDING_SIGN, "100.00");
    postPlan(pendingSign, List.of(item(1, LocalDate.now().plusDays(5), "100.00")))
        .andExpect(status().isOk());
  }

  @Test
  void registerPlanEmptyPlanFails() throws Exception {
    long contractId = newContract(ContractStatus.SIGNED, "100.00");

    postPlan(contractId, List.of())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void recordPaymentExactlyRemainingSettles() throws Exception {
    long contractId = newContract(ContractStatus.SIGNED, "100.00");
    JsonNode plan = registerPlanOk(contractId, List.of(item(1, LocalDate.now().plusDays(5), "100.00")));
    long installmentId = plan.get(0).get("id").asLong();

    payOk(contractId, installmentId, "60.00");

    // 恰好等于剩余金额：应判结清而不是超额
    JsonNode pay = payOk(contractId, installmentId, "40.00");
    assertEquals("SETTLED", pay.get("status").asText());
    assertMoney(pay, "receivedAmount", "100.00");
    assertMoney(pay, "remainingAmount", "0.00");

    JsonNode progress = getJson("/api/contracts/{cid}/installments/progress", contractId);
    assertEquals(1, progress.get("settledCount").asInt());
    assertMoney(progress, "totalReceived", "100.00");
    assertMoney(progress, "totalRemaining", "0.00");
  }

  // ---------- 金额精度 ----------

  @Test
  void recordPaymentThreeDecimalsSettlesWhenFull() throws Exception {
    long contractId = newContract(ContractStatus.SIGNED, "100.00");
    JsonNode plan = registerPlanOk(contractId, List.of(item(1, LocalDate.now().plusDays(5), "100.00")));
    long installmentId = plan.get(0).get("id").asLong();

    // 99.999 按存储精度入为 100.00，收满即结清：已收、剩余、状态三者一致
    JsonNode pay = payOk(contractId, installmentId, "99.999");
    assertEquals("SETTLED", pay.get("status").asText());
    assertMoney(pay, "receivedAmount", "100.00");
    assertMoney(pay, "remainingAmount", "0.00");

    JsonNode installments = getJson("/api/contracts/{cid}/installments", contractId);
    assertEquals("SETTLED", installments.get(0).get("status").asText());
    assertMoney(installments.get(0), "receivedAmount", "100.00");
    assertMoney(installments.get(0), "remainingAmount", "0.00");

    JsonNode progress = getJson("/api/contracts/{cid}/installments/progress", contractId);
    assertMoney(progress, "totalReceived", "100.00");
    assertMoney(progress, "totalRemaining", "0.00");
    assertEquals(1, progress.get("settledCount").asInt());
  }

  @Test
  void recordPaymentThreeDecimalsRoundsDownStaysPartial() throws Exception {
    long contractId = newContract(ContractStatus.SIGNED, "100.00");
    JsonNode plan = registerPlanOk(contractId, List.of(item(1, LocalDate.now().plusDays(5), "100.00")));
    long installmentId = plan.get(0).get("id").asLong();

    // 33.333 向下入为 33.33，未收满保持部分收款
    JsonNode pay = payOk(contractId, installmentId, "33.333");
    assertEquals("PARTIAL", pay.get("status").asText());
    assertMoney(pay, "receivedAmount", "33.33");
    assertMoney(pay, "remainingAmount", "66.67");

    JsonNode progress = getJson("/api/contracts/{cid}/installments/progress", contractId);
    assertMoney(progress, "totalReceived", "33.33");
    assertMoney(progress, "totalRemaining", "66.67");
    assertEquals(0, progress.get("settledCount").asInt());
  }

  @Test
  void registerPlanRoundsItemAmountsToStoragePrecision() throws Exception {
    long contractId = newContract(ContractStatus.SIGNED, "100.00");

    // 33.333 + 66.667 归一后为 33.33 + 66.67 = 100.00，与合同金额一致
    JsonNode plan = registerPlanOk(contractId, List.of(
        item(1, LocalDate.now().plusDays(5), "33.333"),
        item(2, LocalDate.now().plusDays(10), "66.667")));
    assertEquals(2, plan.size());
    assertMoney(plan.get(0), "amount", "33.33");
    assertMoney(plan.get(1), "amount", "66.67");

    long inst1 = plan.get(0).get("id").asLong();
    JsonNode pay = payOk(contractId, inst1, "33.33");
    assertEquals("SETTLED", pay.get("status").asText());

    JsonNode progress = getJson("/api/contracts/{cid}/installments/progress", contractId);
    assertMoney(progress, "totalReceived", "33.33");
    assertMoney(progress, "totalRemaining", "66.67");
    assertEquals(1, progress.get("settledCount").asInt());
  }

  @Test
  void recordPaymentThirdDecimalFourRoundsDown() throws Exception {
    long contractId = newContract(ContractStatus.SIGNED, "100.00");
    JsonNode plan = registerPlanOk(contractId, List.of(item(1, LocalDate.now().plusDays(5), "100.00")));
    long installmentId = plan.get(0).get("id").asLong();

    // 第三位小数为 4：舍去，33.334 → 33.33
    JsonNode pay = payOk(contractId, installmentId, "33.334");
    assertEquals("PARTIAL", pay.get("status").asText());
    assertMoney(pay, "receivedAmount", "33.33");
    assertMoney(pay, "remainingAmount", "66.67");
  }

  @Test
  void recordPaymentThirdDecimalFiveRoundsUp() throws Exception {
    long contractId = newContract(ContractStatus.SIGNED, "100.00");
    JsonNode plan = registerPlanOk(contractId, List.of(item(1, LocalDate.now().plusDays(5), "100.00")));
    long installmentId = plan.get(0).get("id").asLong();

    // 第三位小数为 5：进位，33.335 → 33.34
    JsonNode pay = payOk(contractId, installmentId, "33.335");
    assertEquals("PARTIAL", pay.get("status").asText());
    assertMoney(pay, "receivedAmount", "33.34");
    assertMoney(pay, "remainingAmount", "66.66");
  }

  @Test
  void registerPlanItemRoundingChangesSum() throws Exception {
    long contractId = newContract(ContractStatus.SIGNED, "100.00");

    // 原始合计 33.333+33.333+33.334 = 100.000 与合同金额相等，
    // 但各期先进位到分后 33.33+33.33+33.33 = 99.99，与合同金额不符，应拒绝且不落库
    postPlan(contractId, List.of(
        item(1, LocalDate.now().plusDays(5), "33.333"),
        item(2, LocalDate.now().plusDays(10), "33.333"),
        item(3, LocalDate.now().plusDays(15), "33.334")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("AMOUNT_MISMATCH"));

    JsonNode installments = getJson("/api/contracts/{cid}/installments", contractId);
    assertEquals(0, installments.size());
  }

  // ---------- 逾期统计 ----------

  @Test
  void overdueStatisticsSeparateThreeKinds() throws Exception {
    long contractId = newContract(ContractStatus.SIGNED, "600.00");
    LocalDate yesterday = LocalDate.now().minusDays(1);

    JsonNode plan = registerPlanOk(contractId, List.of(
        item(1, yesterday, "100.00"),
        item(2, yesterday, "200.00"),
        item(3, LocalDate.now().plusDays(30), "300.00")));
    long inst1 = plan.get(0).get("id").asLong();
    long inst2 = plan.get(1).get("id").asLong();

    payOk(contractId, inst1, "100.00"); // 已过期但已收满 → 不算逾期
    payOk(contractId, inst2, "50.00");  // 已过期未收满 → 逾期，剩余 150
    // 第 3 期未到期 → 不算逾期

    JsonNode installments = getJson("/api/contracts/{cid}/installments", contractId);
    assertFalse(installments.get(0).get("overdue").asBoolean());
    assertTrue(installments.get(1).get("overdue").asBoolean());
    assertFalse(installments.get(2).get("overdue").asBoolean());

    JsonNode overdue = getJson("/api/contracts/{cid}/installments/overdue", contractId);
    assertEquals(1, overdue.get("overdueCount").asInt());
    assertMoney(overdue, "overdueAmount", "150.00");
    JsonNode overdueInstallments = overdue.get("overdueInstallments");
    assertEquals(1, overdueInstallments.size());
    assertEquals(2, overdueInstallments.get(0).get("periodNo").asInt());
    assertMoney(overdueInstallments.get(0), "remainingAmount", "150.00");

    JsonNode progress = getJson("/api/contracts/{cid}/installments/progress", contractId);
    assertEquals(1, progress.get("overdueCount").asInt());
    assertEquals(1, progress.get("settledCount").asInt());
    assertEquals(3, progress.get("installmentCount").asInt());
    assertMoney(progress, "totalReceived", "150.00");
    assertMoney(progress, "totalRemaining", "450.00");
  }

  @Test
  void overdueBoundaryDueTodayIsNotOverdue() throws Exception {
    long contractId = newContract(ContractStatus.SIGNED, "300.00");
    LocalDate today = LocalDate.now();

    JsonNode plan = registerPlanOk(contractId, List.of(
        item(1, today.minusDays(1), "100.00"),
        item(2, today, "100.00"),
        item(3, today.plusDays(1), "100.00")));
    assertEquals(3, plan.size());

    // 应收日期当天不算逾期，前一天才算，后一天也不算
    JsonNode installments = getJson("/api/contracts/{cid}/installments", contractId);
    assertTrue(installments.get(0).get("overdue").asBoolean());
    assertFalse(installments.get(1).get("overdue").asBoolean());
    assertFalse(installments.get(2).get("overdue").asBoolean());

    JsonNode overdue = getJson("/api/contracts/{cid}/installments/overdue", contractId);
    assertEquals(1, overdue.get("overdueCount").asInt());
    assertMoney(overdue, "overdueAmount", "100.00");
    assertEquals(1, overdue.get("overdueInstallments").size());
    assertEquals(1, overdue.get("overdueInstallments").get(0).get("periodNo").asInt());

    JsonNode progress = getJson("/api/contracts/{cid}/installments/progress", contractId);
    assertEquals(1, progress.get("overdueCount").asInt());
  }

  // ---------- 跨合同操作 ----------

  @Test
  void operateOtherContractsInstallmentFails() throws Exception {
    long contractA = newContract(ContractStatus.SIGNED, "100.00");
    JsonNode planA = registerPlanOk(contractA, List.of(item(1, LocalDate.now().plusDays(5), "100.00")));
    long installmentOfA = planA.get(0).get("id").asLong();
    long contractB = newContract(ContractStatus.SIGNED, "50.00");

    // 用合同 B 的路径操作属于合同 A 的分期
    postPayment(contractB, installmentOfA, "10.00")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));

    // A 的分期未受影响，B 仍无分期
    JsonNode installmentsA = getJson("/api/contracts/{cid}/installments", contractA);
    assertMoney(installmentsA.get(0), "receivedAmount", "0.00");
    JsonNode installmentsB = getJson("/api/contracts/{cid}/installments", contractB);
    assertEquals(0, installmentsB.size());
  }

  // ---------- 测试数据准备与请求辅助 ----------

  private Long newContract(ContractStatus status, String amount) {
    Contract contract = new Contract();
    contract.setUserId(1L);
    contract.setTemplateId(1L);
    contract.setTitle("分期测试合同");
    contract.setContent("测试内容");
    contract.setAmount(new BigDecimal(amount));
    contract.setStatus(status.name());
    contract.setSigners("[]");
    contractMapper.insert(contract);
    createdContractIds.add(contract.getId());
    return contract.getId();
  }

  private Map<String, Object> item(int periodNo, LocalDate dueDate, String amount) {
    return Map.of("periodNo", periodNo, "dueDate", dueDate.toString(), "amount", new BigDecimal(amount));
  }

  private ResultActions postPlan(long contractId, List<Map<String, Object>> items) throws Exception {
    return mockMvc.perform(post("/api/contracts/{cid}/installments", contractId)
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(Map.of("items", items))));
  }

  private JsonNode registerPlanOk(long contractId, List<Map<String, Object>> items) throws Exception {
    MvcResult result = postPlan(contractId, items).andExpect(status().isOk()).andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private ResultActions postPayment(long contractId, long installmentId, String amount) throws Exception {
    return mockMvc.perform(post("/api/contracts/{cid}/installments/{iid}/payments", contractId, installmentId)
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(
            Map.of("receivedDate", LocalDate.now().toString(), "amount", new BigDecimal(amount)))));
  }

  private JsonNode payOk(long contractId, long installmentId, String amount) throws Exception {
    MvcResult result = postPayment(contractId, installmentId, amount).andExpect(status().isOk()).andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private JsonNode getJson(String url, Object... uriVars) throws Exception {
    MvcResult result = mockMvc.perform(get(url, uriVars)).andExpect(status().isOk()).andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private void assertMoney(JsonNode node, String field, String expected) {
    assertEquals(0, node.get(field).decimalValue().compareTo(new BigDecimal(expected)),
        field + " 应为 " + expected + "，实际为 " + node.get(field).asText());
  }
}
