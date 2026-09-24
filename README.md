# 基于用量的订阅计费引擎（本地模拟版）

在 Spring Boot 工程内实现一套「用量采集 → 周期聚合 → 出账分摊 → 开票 → 支付/重试 → 账本入账」
的最小但完整闭环。**所有外部依赖（数据库、支付通道、总账）均为进程内模拟，
验证不依赖任何外部服务或真实凭据。**

## 1. 总体结构

```
web/            HTTP 入口（用量、订阅、开票/支付）+ 统一异常映射
ingestion/      —（采集逻辑位于 metering/UsageService）
metering/       UsageService（幂等采集/聚合）、TierRatingService（阶梯费率，纯函数）
subscription/   SubscriptionService（订阅、周期、变更与片段切分）
pricing/        PlanCatalog（FREE / PRO / ENTERPRISE 价目）
billing/        BillingService（出账、按比例分摊、分段用量计价）
invoicing/      InvoiceService（幂等开票、支付状态机、重试编排）
payment/        PaymentGateway、LocalPaymentGateway（确定性失败脚本）、RetryPolicy
ledger/         LedgerService（不可变账本、入账幂等、退款、净额守恒）
store/          InMemoryStore（ConcurrentHashMap + 每账号 ReentrantLock）
model/          Money、UsageEvent、Plan/Tier、Bill、Invoice、LedgerEntry 等
config/         JacksonConfig（金额统一两位小数字符串序列化）
```

## 2. 计费与分摊模型

### 2.1 计费周期
- 以订阅 `anchor` 为起点，每 30 天（2,592,000 秒）一个自然周期；
  任意时刻可确定性反查所属周期 `[periodStart, periodEnd)`。
- 账单与发票都以 `(accountId, periodStart)` 为幂等键。

### 2.2 阶梯用量费率（边际单价）
- 每个计量项定义若干 `[from, to)` 区间单价，`to` 为空表示无上限；
- 周期用量与各区间求交，区间内数量 × 区间单价后求和。用量越高，边际单价越低。
- `TierRatingService.rate` 是无副作用纯函数：相同 `(quantity, rate)` 永远得到相同金额，
  因此账单在重放、补算、并发重入时逐分可复现。
- 金额统一 `BigDecimal`、两位小数 `HALF_UP`；无单价的计量项按显式免费（0）处理。

### 2.3 周期内升降级与按比例分摊（秒级精度）
- 订阅的 `planChanges` 是「生效时间点 → 套餐」的有序追加映射，历史不可变。
- 出账时把周期切成若干 `PlanSegment`：
  - **基础费**：每段承担 `baseFee × segmentSeconds / periodSeconds`。
    降级时旧高价套餐只承担生效段份额（等价于返还未使用部分的信用），
    升级时新套餐只承担生效后份额；从模型上杜绝重复扣费与漏计。
  - **用量费**：事件按 `occurredAt` 归属到当时生效的片段，用该片段套餐费率阶梯计价；
    跨变更点的同一计量项因此被精确切给对应套餐，既不双算也不漏算。
  - **尾差**：`HALF_UP` 分摊产生的尾差并入最长片段，保证
    `Σ 明细金额 == 账单合计`，且单一套餐贯穿全周期时严格等于整周期基础费。
- 守恒关系：`账单合计 = Σ 基础费分摊 + Σ 分段用量费`；
  后续账本层面 `净额 = 收入 + 退款（负流水）`。账单、信用（分摊差）、退款三者守恒。

## 3. 用量采集的幂等约定

| 场景 | 处理 | 结论 |
|---|---|---|
| 重复上报（重试/重放/并发双发） | 以调用方 `eventId` 为唯一键，`putIfAbsent` + 账号锁 | 仅首条 `ACCEPTED`，其余 `DUPLICATE`，只计量一次 |
| 乱序到达 | 计量归属按 `occurredAt`，与接收顺序无关 | 聚合结果与到达顺序无关 |
| 窗口内迟到 | 落在当前 30 天接受窗口内即接受并补聚合 | 总量只增加其一次 |
| 超窗迟到/未来时间 | 事件落盘保留（可审计）但标记 `REJECTED`，聚合永久排除 | 结论可解释，不静默吞掉 |

## 4. 发票与支付状态流转

```
                    issue(幂等：同周期仅一张)
   (不存在) ───────────────────────────────► PENDING_PAYMENT
                                                │  charge = NONE
                                                ▼
                                              PAID（终态；唯一一条 PAYMENT 流水）
                                                │
            charge 可重试失败 & 次数 < 3         │ charge 失败 & 次数 = 3
            ◄──────────────────────────────┐    ▼
                                           └► FAILED（终态；不入账，等待人工处理）
```

- `RetryPolicy`：`INSUFFICIENT_FUNDS / CARD_DECLINED / GATEWAY_ERROR` 均可有限重试，
  最多 **3** 次；退避 `2^(n-1)` 秒仅用于展示/调度，自动重试不做真实等待。
- **终态保护**：`PAID` 上再 `pay/retry` 返回冲突（防重复扣款入账）；
  `FAILED` 上再 `pay/retry` 同样被显式拒绝（防静默复活），发票保留可查、金额不丢失。
- 失败原因逐次记录在 `Invoice.attempts` 与 `lastFailureReason` 中，结论可区分、可解释。
- `LocalPaymentGateway` 通过按账号注入的确定性脚本（如
  `[INSUFFICIENT_FUNDS, GATEWAY_ERROR, NONE]`）编排「先失败后成功」或「永远失败」。

## 5. 账本与守恒

- 流水不可变、只追加：`PAYMENT` 为正、`REFUND:*` 为负。
- 每张发票至多一条 `PAYMENT`（业务层账号锁 + 账本二次幂等兜底）。
- 退款不得超过当前净额；`netOf(invoice) = Σ 流水金额`：
  未支付 = 0；已支付未退 = 发票额；部分退后 = 发票额 − 退款额。

## 6. 并发与幂等约定汇总

- **账号锁**：同一账号的「检查-写入」（事件去重、订阅变更、开票、支付状态机）
  在同一把 `ReentrantLock` 内原子完成；不同账号互不阻塞。
- **键级幂等**：事件 `eventId`、账单/发票 `(accountId, periodStart)`、
  账本 `PAYMENT/invoiceId`。并发或重试触发只产生一份事实。
- **HTTP**：参数错误 → 400 `INVALID_REQUEST`；状态机冲突（如终态再支付）→
  409 `ILLEGAL_STATE`，响应体含可解释原因。

## 7. HTTP 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/usage/events` | 上报用量（body: eventId/accountId/metric/quantity/occurredAt） |
| GET  | `/api/usage/accounts/{accountId}/events` | 查询事件（按发生时间排序） |
| POST | `/api/subscriptions` | 创建订阅（accountId/anchor/plan） |
| POST | `/api/subscriptions/{accountId}/changes` | 周期内变更（effectiveAt/plan），重复请求幂等 |
| GET  | `/api/subscriptions/{accountId}` | 查询订阅与变更历史 |
| POST | `/api/billing/invoices` | 幂等开票（accountId/periodStart） |
| GET  | `/api/billing/accounts/{accountId}/invoices?periodStart=...` | 查询发票 |
| POST | `/api/billing/invoices/{id}/pay` | 执行一次支付尝试 |
| POST | `/api/billing/invoices/{id}/retry` | 按策略自动重试至终态 |

金额在 JSON 中形如 `{"currency":"CNY","amount":"149.00"}`（两位小数字符串，避免尾零歧义）。

## 8. 验证方法（不依赖外部服务）

要求 JDK 21+、Maven 3.9+：

```bash
mvn test            # 运行全部 17 个测试（单元 + Spring MVC 集成）
mvn -q -Dtest=UsageIngestionTest test          # 重复/乱序/迟到
mvn -q -Dtest=BillingProrationTest test        # 升降级、分摊、守恒、可复现
mvn -q -Dtest=InvoicePaymentTest test          # 并发开票、失败区分、重试、终态、账本
mvn -q -Dtest=BillingEndToEndIntegrationTest test  # 经真实 HTTP 入口的端到端链路
```

测试日志统一打印「关键输入」与「判定依据」，便于核对结论。覆盖场景：

1. **重复与乱序上报**：同 `eventId` 串行重试 + 2 线程并发仅一条落盘、聚合只计一次；
   乱序/窗口内迟到按 `occurredAt` 得到相同聚合；超窗迟到 `REJECTED` 且不计量。
2. **周期内变更**：半周期升级/降级的基础费 50/50 分摊、用量跨点分段计价、
   `Σ明细 == 合计`、重复出账与事件重放金额不变、无变更时整周期价不被错误削减。
3. **并发开票**：20 线程混合重复创建与开票，只产生一张发票且金额一致。
4. **支付重试**：失败原因逐次可区分；前两次失败第三次成功时仅一条入账；
   持续失败 3 次进入 `FAILED` 终态，之后 `pay/retry` 被拒绝、金额不丢失；
   成功后重复支付被拒绝；部分退款后净额守恒、超额退款被拒绝。
