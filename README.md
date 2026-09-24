# 用量订阅计费引擎（本地模拟版）

一套基于用量的订阅计费与计量流程演示实现：本地内存仓储 + 本地模拟支付通道 +
只追加账本，使**重复上报、周期内变更、支付失败**在任何入口下都得到一致且可解释的结论。
不依赖任何外部服务或真实凭据。

## 1. 运行与验证

```bash
mvn test          # 14 个单测/集成测试，全部本地、确定性
mvn spring-boot:run   # 启动 HTTP 服务（默认 8080）
```

测试使用固定时钟（2026-09-24T12:00:00Z，见 `tests/config/FixedClockConfig.java`）
与每个用例前重置的内存仓储（`TestResetter`），可重复运行、结论稳定。

### HTTP 快速验证

```bash
# 1. 创建订阅（可回填创建时间）
curl -X POST localhost:8080/api/subscriptions -H 'Content-Type: application/json' -d '{
  "subscriptionId":"s1","accountId":"a1","planCode":"BASIC","createdAt":"2026-09-01T00:00:00Z"}'

# 2. 上报用量（同 eventId 重复上报只计一次）
curl -X POST localhost:8080/api/usage-events -H 'Content-Type: application/json' -d '{
  "eventId":"e1","subscriptionId":"s1","meter":"api_calls","quantity":1500,"occurredAt":"2026-09-10T08:00:00Z"}'

# 3. 周期内升级（自 9/16 起 PRO）
curl -X POST localhost:8080/api/subscriptions/s1/plan-change -H 'Content-Type: application/json' -d '{
  "planCode":"PRO","effectiveAt":"2026-09-16T00:00:00Z"}'

# 4. 开票（重复/并发调用只产生一张发票）
curl -X POST localhost:8080/api/subscriptions/s1/invoices -H 'Content-Type: application/json' -d '{"period":"2026-09-15"}'

# 5. 支付 / 重试 / 查询 / 退款 / 账本
curl -X POST localhost:8080/api/invoices/{invoiceId}/pay
curl -X POST localhost:8080/api/invoices/{invoiceId}/retry
curl -X POST localhost:8080/api/invoices/{invoiceId}/refund
curl localhost:8080/api/invoices/{invoiceId}
curl localhost:8080/api/subscriptions/s1/ledger
```

测试 profile（`spring.profiles.active=test`，测试自动启用）额外提供故障注入接口：

```bash
curl -X POST localhost:8080/api/test-support/payment-script -H 'Content-Type: application/json' -d '{
  "accountId":"s1","outcomes":["INSUFFICIENT_FUNDS","CARD_DECLINED"]}'
# 此后该账户前两次扣款分别返回“余额不足”“卡过期”，第三次起默认成功
```

## 2. 领域与计费模型

### 2.1 套餐与用量

- `Plan`：月基础费 + 每个计量项（meter）的阶梯费率表 `(from, to] -> 单价`，
  预置 `BASIC`（100.00/月，api_calls 1000 以上 0.02）与
  `PRO`（300.00/月，api_calls 5000 以上 0.01）。
- **阶梯费率分段计价**：周期内同一计量项按**套餐时间片**聚合总量，落入每一档的部分
  分别按该档单价计费后求和（不是整单套一个档）。例：1500 次在 BASIC 下
  = 1000×0 + 500×0.02 = 10.00。
- 金额统一 `BigDecimal`、2 位小数、`HALF_UP`；所有计算为纯函数，重放结果确定。

### 2.2 用量采集与周期聚合

- 周期固定为 **UTC 自然月** `[月初, 下月初)`，与服务器默认时区无关。
- 事件以 `eventId`（上报方提供）为全局幂等键：
  - 首次 -> `ACCEPTED`；同 eventId 同内容 -> `DUPLICATE`；同 eventId 不同内容 ->
    `DUPLICATE`（原因注明“内容冲突”，保留首次事件）；
  - 非法数量/时间、未知订阅等 -> `REJECTED`，均携带可解释原因。
- 归属与排序以 **occurredAt（事件发生时间）** 为准，与接收顺序无关：
  乱序（先发后到）、迟到（跨周期才到）都能进入正确周期；仓储按 occurredAt
  升序返回，同一输入重放的聚合与账单金额完全一致。
- 并发首报由 `ConcurrentHashMap.putIfAbsent` 原子去重，落败方等价于重复上报。

### 2.3 周期内变更与按比例分摊

变更只是在订阅上追加一个“自 effectiveAt 起生效”的版本，**不立即交易**，
出账时把周期切成首尾相接的时间片（按天）。发票基础费用三类行表达：

| 行类型 | 方向 | 含义 |
|---|---|---|
| `BASE_FULL` | 正（1 行） | 周期起始套餐整月基础费 |
| `BASE_CREDIT` | 负（每变更点 1 行） | 旧套餐在“新片”本应收的费用，作为信用额度冲回 |
| `BASE_PRORATED` | 正（每变更点 1 行） | 新套餐在同一新片按天（天数/月天数）计收 |
| `ROUNDING_ADJUSTMENT` | 正/负（至多 1 行） | 逐行 HALF_UP 与精确按比例分摊之间的分级舍入差（至多几分） |

净额恒等于 `Σ 每片月租 × 片天数/月天数`：升级表现为补差价、降级表现为信用退差。
用量费按“事件时刻所属套餐片 + meter”聚合后套阶梯（行类型 `USAGE_TIER`）。

示例（9 月共 30 天，9/16 BASIC→PRO）：
`100.00（整月）− 50.00（BASIC 后 15 天信用）+ 150.00（PRO 后 15 天）= 200.00`。

### 2.4 账本与守恒

账本（`LedgerEntry`）只追加，金额非负、方向由类型表达：

- `CHARGE`：发票正向行（基础费、用量费）入账；
- `CREDIT`：发票信用行（变更产生的冲回）入账；
- `REFUND`：退款实际退回的现金。

**仅支付成功才入账**；入账逐行登记发票各自行。守恒式（单订阅维度）：

```
SUM(CHARGE) = SUM(CREDIT) + SUM(REFUND)
```

支付成功后未退款时，差额 `CHARGE − CREDIT` 是企业实收现金；对已支付发票执行退款后，
按实收净额登记一笔 `REFUND`，发票置 `VOID`，等式成立（见
`GET /api/subscriptions/{id}/ledger` 的 `conserved` 字段）。失败的支付尝试不产生任何分录。

## 3. 发票与支付状态流转

```
                         并发/重试开票
   (不存在) ──issue──▶ OPEN ──────────────▶ 返回同一张 OPEN 发票（created=false）
                         │
            pay 成功      │ pay/retry 失败
                         ▼
                       PAID（终态）        PAYMENT_FAILED ──retry──▶ RETRYING
                         ▲                       │  ▲                    │
                         └───── retry 成功 ──────┘  └──── 失败但未达上限 ─┘
                                                 │ 失败且 attemptCount==3
                                                 ▼
                                      MAX_RETRIES_EXCEEDED（终态，保留最后失败原因）
   PAID ──refund──▶ VOID（终态，登记 REFUND）
```

- 发票唯一约束为 `(subscriptionId, periodStart)`，由仓储原子保证 +
  订阅级锁双保险：并发开票只有一个 `insert` 成功，其余回读同一张票，**一周期一票**。
- 支付最大尝试次数 3（首次 pay + 2 次 retry）；失败原因枚举可区分：
  `INSUFFICIENT_FUNDS / CARD_DECLINED / GATEWAY_TIMEOUT / INVALID_ACCOUNT`。
- 入口约束：`pay` 只接受 `OPEN`，`retry` 只接受 `PAYMENT_FAILED`；
  在 `PAID / VOID / MAX_RETRIES_EXCEEDED` 上重复调用一律幂等返回，
  **不新增扣款尝试、不重复入账、不静默丢失**。
- 通道幂等键为 `PAY:{invoiceId}:{attemptNo}`，模拟通道对同键返回同一结果与流水号，
  网络重试不会在通道侧重复扣款。
- 发票级互斥锁串行化 pay/retry/refund。

## 4. 幂等约定一览

| 场景 | 幂等键/约束 | 重放结论 |
|---|---|---|
| 用量上报 | `eventId` 全局唯一 | 首次 ACCEPTED，之后恒 DUPLICATE，仓储仅 1 条 |
| 周期内变更 | 同套餐变更忽略；版本点不得早于上一版本 | 结论一致，版本链不乱序 |
| 开票 | `(subscriptionId, period)` 唯一 | 同一张发票、同一金额，`created=false` |
| 通道扣款 | `PAY:发票:尝试序号` | 同键同结果同流水号 |
| 支付/重试/退款 | 发票级锁 + 状态机守卫 | 终态上重复调用不产生新交易 |

## 5. 代码结构

```
domain/   Money、Plan、MeterTier、Subscription(Version)、UsageEvent、
          Invoice(LineItem)、PaymentAttempt、LedgerEntry 与各状态/原因枚举
repo/     仓储接口 + ConcurrentHashMap/CopyOnWriteArrayList 内存实现 + 按键锁
service/  PeriodService（周期）、PricingService（阶梯计价）、
          UsageIngestionService（采集）、SubscriptionService（变更）、
          InvoiceService（分摊开票）、PaymentGateway/SimulatedPaymentGateway、
          PaymentService（状态机/重试/退款）、LedgerService（守恒）
web/      REST 控制器、请求/视图 DTO、统一异常处理（400/404/409）
config/   Clock Bean（测试可替换为固定时钟）、套餐种子
tests/    UsageIngestionTest、PlanChangeProrationTest、ConcurrentInvoiceTest、
          PaymentRetryTest、BillingEndToEndApiTest
```

关键日志覆盖所有入口的**关键输入与判定依据**（ACCEPTED/DUPLICATE/REJECTED 原因、
聚合总量、发票各行、通道扣款结果、状态推进、入账后守恒校验），可用
`mvn test` 输出或运行期日志审计每一步结论。

## 6. 已知边界（演示版）

- 全部状态在内存，重启即清空；生产应替换为数据库事务/行锁，
  幂等键与 `(订阅,周期)` 唯一约束下沉到存储层。
- 计费周期固定 UTC 自然月、按天分摊；含税、多币种、预付款余额、
  跨月退款冲销等未纳入本演示。
