# 用量订阅计费引擎（本地模拟版）

一套基于用量的订阅计费与计量流程演示实现：本地内存仓储 + 本地模拟支付通道 +
只追加账本，使**重复上报、周期内变更、支付失败、结算后补报**在任何入口下都得到一致且
可解释的结论。不依赖任何外部服务或真实凭据。

## 1. 运行与验证

```bash
mvn test          # 24 个单测/集成测试，全部本地、确定性
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

# 6. 结算后补报：账单已出（甚至已支付/已作废）后，再上报 occurredAt 落在该账期的用量
curl -X POST localhost:8080/api/usage-events -H 'Content-Type: application/json' -d '{
  "eventId":"e-late-1","subscriptionId":"s1","meter":"api_calls","quantity":600,
  "occurredAt":"2026-09-20T20:00:00Z"}'
# 触发补差结算（同内容重放/并发只返回同一张补差单，created=false）
curl -X POST localhost:8080/api/subscriptions/s1/adjustments -H 'Content-Type: application/json' -d '{"period":"2026-09-15"}'
# 查询 / 列出 / 显式收取（重试）补差
curl localhost:8080/api/adjustments/{adjustmentId}
curl 'localhost:8080/api/subscriptions/s1/adjustments?period=2026-09-15'
curl -X POST localhost:8080/api/adjustments/{adjustmentId}/collect
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

### 2.5 结算后补差（出账后的迟到用量）

账单出出来之后，仍可能陆续收到 `occurredAt` 落在该账期内的迟到用量。采集入口对此**完全
不变**：迟到事件照旧 `ACCEPTED` 入库（eventId 幂等、按 occurredAt 归属周期），既不丢单，
也不改已经出去的那张账单。补差由独立入口
`POST /api/subscriptions/{id}/adjustments` 显式触发结算。

- **触发条件**：该账期已经存在发票，无论其状态是 `OPEN / PAID / PAYMENT_FAILED /
  MAX_RETRIES_EXCEEDED / VOID`；账期没出过账单时调用补差属于用法错误（返回 409，
  请走正常开票）。
- **补差单（Adjustment）是独立单据**：有自己的编号、序号、状态机、收款尝试与账本分录，
  通过 `invoiceId` 关联原账单。**原账单的编号、明细行、金额永不被修改或重开。**
- **计算口径（整期重算，不是只给新事件套价）**：

  ```
  补差金额 = 用当前能看到的全部用量（含全部迟到部分）对整期重新算出的应计总额
           − 该账期此前已经计过的金额
  此前已计金额 = 原账单净额 + 该账期既往所有补差金额之和
  ```

  重算与正常开票走**同一个整期计费器**（`PeriodBillingCalculator`）：基础费仍按套餐时间片
  按天分摊、用量费仍按“事件时刻所属套餐片 + meter”聚合**整期总量**后套阶梯费率、舍入对齐
  口径一致。因此补进来的量若让整期累计用量跨进更高阶梯档，是按整期总量重算，绝不只对新
  事件单独套一次价；周期内多次变更、变更点落在一天中间，重算口径也与正常出账完全一致。
  示例：BASIC 下出账前 900 次（免费档内，用量费 0），出账后迟到 200 次，整期 1100 次，
  正确补差 = 100 × 0.02 = **2.00**（若错误地只对 200 个新事件套价会错算成 4.00）。
- **结果可解释**：补差结果给出 `recalculatedTotal`（重算总额）、`previouslyBilled`
  （此前已计）、`amount`（净增）以及 `basisEventIds`（重算所依据的整期全部事件 id）。
  迟到用量只可能让总额不减，故补差金额恒为非负；重算后总额无变化时明确为 **0**，状态直接
  PAID，**不产生任何费用与账本分录**。

#### 与支付/退款状态的关系（入账口径一致，不重复扣款、不漏计）

补差单只开一张净增额的单据，收款与入账严格沿用支付/退款口径：

| 原账单状态 | 补差生成时 | 后续收款 |
|---|---|---|
| `PAID`（已支付） | 立即就**净增额**向通道收款一次（通道键 `ADJ:{补差}:{尝试序号}`，绝不重扣原账单金额）；成功登一笔 `CHARGE`，失败留 `PAYMENT_FAILED` | `POST /api/adjustments/{id}/collect` 重试 |
| `OPEN` | 补差挂账 `OPEN`，**不提前扣款、不入账** | 原账单日后支付成功时随账单一并收取 |
| `PAYMENT_FAILED` / `MAX_RETRIES_EXCEEDED` | 补差挂账 `OPEN`，不静默丢单 | 可显式 `collect` 单独收取，与原账单是否被救活解耦 |
| `VOID`（已退款作废） | 补报照样接收、补差挂账 `OPEN` | 可显式 `collect` 收取；不影响已作废的原账单 |

原账单退款时，所有**已收款**的补差各登一笔 `REFUND` 并置 `VOID`，连同原账单净额一起退还，
账本仍满足 `CHARGE = CREDIT + REFUND`；未收款的挂账补差不退现金（本来就没扣）。

#### 幂等与守恒约定

- **幂等键（内容寻址）**：补差内容键 =
  `SHA-256(该账期当前可见全部事件 id 的有序集合)`。同一批输入无论重放、并发还是重试，
  看到的事件集合相同 → 内容键相同 → 仓储 `(订阅,周期,内容键)` 唯一约束保证**只生成一张补差
  单**（重复调用 `created=false`，金额完全一致、可复现）；只有真的来了新用量、事件集合发生
  变化，才产生下一张补差单。
- **逐批望远镜求和**：每来一批新用量产生一张新补差，序号自增。恒有

  ```
  原账单净额 + 该账期所有补差金额之和 = 用最终全部用量重新计算出的应计总额
  ```

  因为每张补差 = “当时重算总额 − 此前已计”，多批补差累加后中间项相消，只剩最终总额。
- **账本守恒**：补差正数仅在收款成功时登一笔 `CHARGE`（关联原账单号），随退款冲回
  `REFUND`；零补差、收款失败均不产生分录。账本始终满足
  `SUM(CHARGE) = SUM(CREDIT) + SUM(REFUND)`。

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

补差单自己的状态机（与原账单解耦的独立单据）：

```
  生成(amount=0) ─────────────────────────────▶ PAID（终态，无分录）
  生成(amount>0, 原账单 PAID) ─收款成功────────▶ PAID（终态，登 CHARGE）
                                  └收款失败────▶ PAYMENT_FAILED ─collect─▶ PAID
  生成(amount>0, 原账单 OPEN/失败/VOID) ───────▶ OPEN
        ├─ 原账单支付成功联动收取 / 显式 collect 成功 ─▶ PAID
        └─ 显式 collect 失败 ─▶ PAYMENT_FAILED ─collect─▶ PAID
  PAID 补差随原账单 refund ─▶ VOID（终态，登 REFUND）
```

- 补差结算的加锁顺序为订阅锁 → 原账单锁；补差收款/冲回只持原账单锁，
  与 pay/retry/refund 互斥，全系统无反向加锁路径，不会死锁。
- **对原有入口的兼容范围**：用量采集、周期归属、开票、支付/重试/退款、账本查询的请求与
  响应均保持向后兼容；补差是**叠加的一层**（新端点 `/adjustments`、新单据类型、新仓储），
  不改动既有用量事件与发票数据结构、不重开或改写旧账单。原账单支付成功会额外联动收取其
  挂账补差、退款会额外联动冲回已收补差——这是在既有账本恒等式内新增分录，不破坏旧结论。

## 4. 幂等约定一览

| 场景 | 幂等键/约束 | 重放结论 |
|---|---|---|
| 用量上报 | `eventId` 全局唯一 | 首次 ACCEPTED，之后恒 DUPLICATE，仓储仅 1 条 |
| 周期内变更 | 同套餐变更忽略；版本点不得早于上一版本 | 结论一致，版本链不乱序 |
| 开票 | `(subscriptionId, period)` 唯一 | 同一张发票、同一金额，`created=false` |
| 结算后补差 | `(subscriptionId, period, 内容键)` 唯一，内容键=账期内全部事件 id 有序集合的 SHA-256 | 同批输入重放/并发只返回同一张补差单；事件集合变化才出新补差 |
| 补差收款 | `ADJ:补差单:尝试序号` | 已收款幂等返回，不重复扣款入账 |
| 通道扣款 | `PAY:发票:尝试序号` | 同键同结果同流水号 |
| 支付/重试/退款 | 发票级锁 + 状态机守卫 | 终态上重复调用不产生新交易；退款联动冲回已收补差 |

## 5. 代码结构

```
domain/   Money、Plan、MeterTier、Subscription(Version)、UsageEvent、
          Invoice(LineItem)、PaymentAttempt、Adjustment、LedgerEntry 与各状态/原因枚举
repo/     仓储接口 + ConcurrentHashMap/CopyOnWriteArrayList 内存实现 + 按键锁
service/  PeriodService（周期）、PricingService（阶梯计价）、
          PeriodBillingCalculator（整期计费：开票与补差重算共用的唯一口径）、
          UsageIngestionService（采集）、SubscriptionService（变更）、
          InvoiceService（分摊开票）、PostBillingAdjustmentService（结算后补差/收款/冲回）、
          PaymentGateway/SimulatedPaymentGateway、
          PaymentService（状态机/重试/退款，并联动补差收款与冲回）、LedgerService（守恒）
web/      REST 控制器、请求/视图 DTO、统一异常处理（400/404/409）
config/   Clock Bean（测试可替换为固定时钟）、套餐种子
tests/    UsageIngestionTest、PlanChangeProrationTest、ConcurrentInvoiceTest、
          PaymentRetryTest、BillingEndToEndApiTest、
          PostBillingAdjustmentTest（9 个补差服务用例）、
          PostBillingAdjustmentApiTest（补差 HTTP 链路）
```

关键日志覆盖所有入口的**关键输入与判定依据**（ACCEPTED/DUPLICATE/REJECTED 原因、
聚合总量、发票各行、通道扣款结果、状态推进、入账后守恒校验），可用
`mvn test` 输出或运行期日志审计每一步结论。

## 6. 已知边界（演示版）

- 全部状态在内存，重启即清空；生产应替换为数据库事务/行锁，
  幂等键、`(订阅,周期)` 与补差 `(订阅,周期,内容键)` 唯一约束下沉到存储层。
- 计费周期固定 UTC 自然月、按天分摊；含税、多币种、预付款余额、
  跨月退款冲销等未纳入本演示。
- 补差按设计只处理“迟到用量导致的净增（非负）”；若在出账后回填一次会降低旧账期费用的
  套餐降级，重算出现负差时系统明确报错而非静默错账，这类反向调整未在演示范围内。

## 7. 补差专项的本地验证

```bash
# 只跑补差相关测试（服务层 9 例 + HTTP 1 例），日志含“关键输入/判定依据”
mvn test -Dtest='PostBillingAdjustmentTest,PostBillingAdjustmentApiTest'
mvn test   # 全量 24 例：原有重复上报/乱序迟到/周期内变更/并发开票/支付重试均不回归
```

覆盖点：出账后补报触发补差；补报跨更高阶梯时的整期重算（2.00 而非 4.00）；
同批输入 12 线程并发 + 重放只产生一张补差、金额一致、只入一笔账；
连续多批补差望远镜求和（原账单净额 + 补差之和 = 最终全量重算总额）与退款后守恒；
重算无变化为零且不产生分录；原账单编号/明细/金额不被改写；
已支付账期补差收款失败可重试、OPEN/失败终态/VOID 账期补报不丢单可显式收取；
多次变更与变更点落在一天中间时重算口径等于独立全量开票。
