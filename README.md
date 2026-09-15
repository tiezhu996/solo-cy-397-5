# 合同模板生成与法律工单 API

```bash
cp .env.example .env
docker compose up -d --build
```

合同模板生成与法律工单 API 提供合同模板管理、变量填充生成、签署状态跟踪、法律咨询工单和法律 FAQ 检索能力。

## 项目主要功能

- 管理租赁、劳动、借款、合作、保密协议模板与占位变量。
- 根据变量生成纯文本/HTML 合同，并预留 wkhtmltopdf 导出 PDF。
- 合同状态支持草稿、待签署、已签署、已过期。
- 合同分期收款：登记分期计划、逐期收款、进度与逾期统计。
- 法律工单提交、分配、回复和关闭。
- 法律 FAQ 分类维护与关键词搜索。
- 用户合同库与模板收藏。

## 本地开发

```bash
cd backend
mvn spring-boot:run
```

## 技术栈

| 类型 | 技术 |
| --- | --- |
| 后端 | Spring Boot + Java 17 |
| ORM | MyBatis-Plus |
| 数据库 | MySQL 8.0 |
| 认证 | JWT |
| PDF | wkhtmltopdf |

## 目录结构

```text
.
├── backend
│   ├── src/main/java/com/contractapi
│   └── src/main/resources
├── database
│   └── init.sql
└── docker-compose.yml
```

## 主要 API

- `GET /api/templates` 模板列表
- `POST /api/templates` 新增模板
- `POST /api/contracts/generate` 合同生成
- `PATCH /api/contracts/{id}/status` 更新签署状态
- `GET /api/contracts` 用户合同库
- `POST /api/contracts/{id}/installments` 登记分期计划
- `GET /api/contracts/{id}/installments` 分期列表
- `POST /api/contracts/{id}/installments/{iid}/payments` 登记收款
- `GET /api/contracts/{id}/installments/progress` 收款进度
- `GET /api/contracts/{id}/installments/overdue` 逾期明细
- `POST /api/tickets` 提交法律工单
- `POST /api/tickets/{id}/replies` 添加工单回复
- `GET /api/knowledge` 搜索法律 FAQ

## 分期收款说明

- 生成合同时通过 `amount` 字段记录合同金额；草稿（DRAFT）或已过期（EXPIRED）合同不允许登记分期。
- 登记分期计划时各期金额之和必须与合同金额一致，否则返回 `AMOUNT_MISMATCH` 失败；每份合同只能登记一次计划。
- 每期可多次登记收款（实收日期 + 金额），剩余金额随之更新，收满自动标记 `SETTLED`；同一期累计收款不得超过应收金额，超出返回 `PAYMENT_EXCEEDS_DUE`。
- 应收日期已过且未收满的期数计为逾期，可通过 progress / overdue 接口查询进度与逾期明细。
- 分期与收款记录持久化在 MySQL（`contract_installments` / `installment_payments`），重启后可读回。
- 已有数据卷升级时请先备份，或执行 `docker compose down -v` 后重新初始化以应用新表结构。

## 环境变量说明

| 变量 | 说明 |
| --- | --- |
| `COMPOSE_PROJECT_NAME` | Compose 项目名，默认 `contractapi` |
| `MYSQL_*` | MySQL 数据库配置 |
| `JWT_SECRET` | JWT 签名密钥 |

## License

MIT
