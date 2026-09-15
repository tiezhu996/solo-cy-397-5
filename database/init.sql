CREATE TABLE IF NOT EXISTS contract_templates (id BIGINT PRIMARY KEY AUTO_INCREMENT, type VARCHAR(32), title VARCHAR(120), content TEXT, variables JSON);
CREATE TABLE IF NOT EXISTS contracts (id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT, template_id BIGINT, title VARCHAR(120), content MEDIUMTEXT, amount DECIMAL(15,2), status VARCHAR(32), signed_at DATETIME, signers JSON);
CREATE TABLE IF NOT EXISTS legal_tickets (id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT, type VARCHAR(32), description TEXT, status VARCHAR(32), attachments JSON);
CREATE TABLE IF NOT EXISTS legal_faq (id BIGINT PRIMARY KEY AUTO_INCREMENT, category VARCHAR(60), question VARCHAR(200), answer TEXT);

-- 合同分期收款
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
);
CREATE TABLE IF NOT EXISTS installment_payments (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  installment_id BIGINT NOT NULL,
  received_date DATE NOT NULL,
  amount DECIMAL(15,2) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_installment (installment_id)
);

-- 兼容已有数据库：contracts 缺少 amount 列时补齐（重复执行安全）
SET @has_amount := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'contracts' AND COLUMN_NAME = 'amount');
SET @alter_amount := IF(@has_amount = 0, 'ALTER TABLE contracts ADD COLUMN amount DECIMAL(15,2) NULL AFTER content', 'SELECT 1');
PREPARE stmt_amount FROM @alter_amount;
EXECUTE stmt_amount;
DEALLOCATE PREPARE stmt_amount;

INSERT INTO legal_faq(category, question, answer) VALUES ('合同纠纷','合同逾期未签署怎么办','可先发出书面催告并保存沟通证据。') ON DUPLICATE KEY UPDATE question=question;
