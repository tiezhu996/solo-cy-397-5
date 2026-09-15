package com.contractapi.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 修复历史精度问题产生的脏数据：已收满（received_amount >= amount）但状态未结清的分期，
 * 启动时幂等校正为 SETTLED，保证已收、剩余、结清状态三者一致。需在建表之后执行。
 */
@Component
@Order(2)
public class InstallmentStatusRepair implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(InstallmentStatusRepair.class);
  private final JdbcTemplate jdbcTemplate;

  public InstallmentStatusRepair(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void run(ApplicationArguments args) {
    int repaired = jdbcTemplate.update(
        "UPDATE contract_installments SET status = 'SETTLED' WHERE status <> 'SETTLED' AND received_amount >= amount");
    if (repaired > 0) {
      log.info("已将 {} 条已收满但未结清的分期记录校正为 SETTLED", repaired);
    }
  }
}
