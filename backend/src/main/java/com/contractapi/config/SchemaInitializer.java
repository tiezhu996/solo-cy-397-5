package com.contractapi.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时保证应用所需表结构存在：
 * 已有数据卷不会重跑 init.sql，这里幂等建表/补列，保证合同与分期收款记录可读写。
 */
@Component
public class SchemaInitializer implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);
  private final JdbcTemplate jdbcTemplate;

  public SchemaInitializer(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void run(ApplicationArguments args) {
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS contracts (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          user_id BIGINT,
          template_id BIGINT,
          title VARCHAR(120),
          content MEDIUMTEXT,
          amount DECIMAL(15,2),
          status VARCHAR(32),
          signed_at DATETIME,
          signers JSON
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS contract_installments (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          contract_id BIGINT NOT NULL,
          period_no INT NOT NULL,
          due_date DATE NOT NULL,
          amount DECIMAL(15,2) NOT NULL,
          received_amount DECIMAL(15,2) NOT NULL DEFAULT 0,
          status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          UNIQUE KEY uk_contract_period (contract_id, period_no)
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS installment_payments (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          installment_id BIGINT NOT NULL,
          received_date DATE NOT NULL,
          amount DECIMAL(15,2) NOT NULL,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          KEY idx_installment (installment_id)
        )
        """);
    Integer hasAmount = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'contracts' AND COLUMN_NAME = 'amount'",
        Integer.class);
    if (hasAmount != null && hasAmount == 0) {
      log.info("contracts 表缺少 amount 列，执行 ALTER TABLE 补齐");
      jdbcTemplate.execute("ALTER TABLE contracts ADD COLUMN amount DECIMAL(15,2) NULL AFTER content");
    }
  }
}
