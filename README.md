# 用量订阅计费引擎（本地模拟版）

一套基于用量的订阅计费与计量流程演示实现：本地内存仓储 + 本地模拟支付通道 +
只追加账本，使**重复上报、周期内变更、支付失败**，以及**账单出具后账期内用量的迟到补报**，
在任何入口下都得到一致且可解释的结论。不依赖任何外部服务或真实凭据。

## 1. 运行与验证

```bash
mvn test          # 34 个单测/集成测试，全部本地、确定性
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

# 6. 出账后迟到补报：用量仍走原入口正常接收（不丢弃、不影响原票），随后单独触发补差
curl -X POST localhost:8080/api/usage-events -H 'Content-Type: application/json' -d '{
  "eventId":"e-late","subscriptionId":"s1","meter":"api_calls","quantity":4000,"occurredAt":"2026-09-08T08:00:00Z"}'
curl -X POST localhost:8080/api/subscriptions/s1/adjustments -H 'Content-Type: application/json' -d '{
  "period":"2026-09-15","batchId":"batch-001"}'
# -> 独立补差单（delta=整期重算净增），原发票编号/明细/金额不变；batchId 为重放/并发幂等键

# batchId 可省略：请求体只给 period。同一批输入重复提交（超时重试/处理两遍）
# 由“整期依据事件集合”的内容指纹幂等，第二次起回读同一笔，金额/单号与第一次完全一致。
curl -X POST localhost:8080/api/subscriptions/s1/adjustments -H 'Content-Type: application/json' -d '{
  "period":"2026-09-15"}'
curl -X POST localhost:8080/api/subscriptions/s1/adjustments -H 'Content-Type: application/json' -d '{
  "period":"2026-09-15"}'   # created=false，adjustmentId/delta 与上一次相同
curl localhost:8080/api/adjustments/{adjustmentId}
curl -X POST localhost:8080/api/adjustments/{adjustmentId}/pay
curl -X POST localhost:8080/api/adjustments/{adjustmentId}/retry
curl -X POST localhost:8080/api/adjustments/{adjustmentId}/refund
```

测试 profile（`spring.profiles.active=test`，测试自动启用）额外提供故障注入接口：

```bash
curl -X POST localhost:8080/api/test-support/payment-script -H 'Content-Type: application/json' -d '{
  "accountId":"s1","outcomes":["INSUFFICIENT_FUNDS","CARD_DECLINED"]}'
# 此后该账户前两次扣款分别返回“余额不足”“卡过期”，第三次起默认成功
```

**本地验证结算后补差**：先出账并支付一张 9 月发票，再用上面的第 6 步补报一条 9 月迟到用量、
带 `batchId` 触发补差；重复触发应得到同一个 `adjustmentId` 且 `created=false`，
支付补差后查 `/api/subscriptions/s1/ledger` 可见 `charge = 原发票净额 + 补差`，
作废原发票后补差被联动退回且 `conserved=true`。

**复现“不传 batchId 的重放/并发/重试”修复（`NullBatchIdempotencyTest`）**：

```bash
# 只跑该用例类（7 个场景）
mvn test -Dtest=NullBatchIdempotencyTest
```

覆盖：①不带 `batchId` 同批输入重放返回同一单号/同一金额；②16 线程无键并发只落一笔、
无人拿到 0；③整期确无净增时 `delta=0` 且不落单；④已存在未收款补差时（不带键、或换新
batchId）相同输入回读同一笔；⑤补差收款失败挂起后重放仍回读该笔并可继续重试；
⑥混用带键/不带键 12 线程并发只落一笔；⑦多批新用量逐笔累加、交错重放一致且望远镜守恒
（`原账单净额 + Σ补差 = 最终全量重算应计`，一笔补差只收一次款）。
HTTP 入口的对应用例见 `AdjustmentEndToEndApiTest#replayWithoutBatchIdOverHttpIsIdempotent`。

关键日志包括 `补差结算输入 / 补差判定依据（原发票净额、历史补差、重算应计、delta、
内容指纹）/ 补差输入已存在，幂等返回同一份 / 补差单已生成 /
补差收款成功|失败 / 补差退款完成`。

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

### 2.5 结算后补报与补差（Adjustment）

账单出具之后，仍会陆续收到发生时间落在该账期内的迟到用量。本层在既有能力上
**叠加**一层补差，而不是重做账单：

- **接收不变**：迟到事件继续走既有 `/api/usage-events`，按 `eventId` 幂等、按 `occurredAt`
  归属账期，正常接收、不丢弃，也**不会改动已出发票的编号、明细与金额**。
- **触发条件**：该账期已存在发票（无论 `OPEN / PAYMENT_FAILED / PAID / VOID`）；
  对尚未出账的账期调用补差返回 409（请先开票），不悄悄开票、不静默丢单。
- **计算口径**（与正常出账完全相同，复用同一个 `BillingCalculator`）：

  ```
  recalculatedTotal = 用当前全部用量（含迟到部分）对整期重新算出的应计总额
  previouslyBilled  = 原发票净额 + 该账期此前所有补差净额
  delta             = recalculatedTotal − previouslyBilled
  ```

  迟到量把整期累计推进更高阶梯档时，按**整期累计量**重新分段计价
  （例：PRO 免费 5000，出账时累计 1000 免费，补报后累计 5500 →
  补收 `(5500−5000)×0.01 = 5.00`，而不是只对新到的 4500 单独套价得到 0）。
  多次变更、变更点落在一天中间时，时间片与按天分摊口径与正常出账一致。
- **补差为零的唯一判定**：用当前能看到的全部用量把整期重新算一遍，
  **应计总额与“原发票净额 + 该账期此前所有补差净额”完全相等（一点净增都没有）**，
  且不存在与本次输入同内容、尚未收款的既有补差单时，`delta` 才明确为 `0`，
  不生成补差单、不产生任何费用与分录。若此前已经为同一批输入生成过补差（哪怕尚未收款），
  重放必须回读那一笔（金额、单号一致），**绝不允许把已产生、还没收款的补差藏成 `delta=0`
  与空单号**。
- **结果可解释**：补差单记录 `recalculatedTotal / previouslyBilled / delta /
  basisEventIds（依据的全部 eventId）/ basisSummary`，可追溯“净增了多少、依据了哪些用量”。

**望远镜守恒**：每来一批新用量产生一份新补差，

```
原发票净额 + Σ(该账期所有补差 delta) = 用最终全部用量重新计算的应计总额
```

因此补差金额对输入确定、可复现；同一批输入无论重放、并发还是重试，金额与补差单号完全一致。

#### 2.5.1 不传 batchId 时的返回语义（内容指纹幂等）

调用方**可以省略**可选的 `batchId`。省略后系统不再依赖调用方给键，而是对“本次整期重算所
依据的全部 `eventId` 集合（排序后）”计算一个确定性**内容指纹（SHA-256）**，用
`(subscriptionId, period, contentFingerprint)` 兜底幂等：

| 情形 | 返回语义 |
|---|---|
| 同一批输入（账期可见用量集合不变）重放/并发/重试，**不带 batchId** | 恒回读**同一笔**补差单：同一 `adjustmentId`、同一 `delta`，仅首次 `created=true`；无论那笔补差当前是 `OPEN / PAYMENT_FAILED / PAID / REFUNDED` 都不藏单 |
| 同一批输入先带 `batchId=A` 生成、补差**尚未收款**，后用 `batchId=B` 或不带键重提 | 回读**同一笔**未收款补差（金额、单号一致），不生成第二笔、不重复收款 |
| 同一批输入对应的补差**已经收款（PAID/REFUNDED）**，再用一个**新的 batchId** 提交 | 望远镜口径下新增净增恰为 0：返回 `delta=0`、不落第二笔（同 batchId 重放仍回读原单） |
| 整期确实没有任何净增（重算应计 == 此前已计，且无同内容补差） | `delta=0`、`adjustmentId=null`、不落单；重放结论不变 |
| 又到了一批**新**用量（事件集合变大，指纹变化） | 产生新一笔补差；多笔补差逐笔累加满足望远镜守恒 |

> 关键不变量：**只要一笔补差已经产生且还没收款，任何形式的重复提交（重放、并发、超时重试，
> 带或不带 `batchId`）拿到的都是那笔补差本身，绝不会出现“第二次返回 0、单号为空、钱却挂在
> 待收款”的对不上账情况。**

### 2.6 补差的幂等、收款与状态

- 批次幂等键为 `(subscriptionId, period, batchId)`（由上报方提供，**可空**）：
  同键重放/并发只产生一份补差单，其余调用 `created=false` 回读同一份，不重复生成、不重复入账；
  **未提供 `batchId` 时由“整期依据事件集合”的内容指纹 `(subscriptionId, period,
  contentFingerprint)` 兜底**，同样保证同一批输入重放/并发/重试只产生一份补差单、
  返回同一金额与同一单号。所有判定都在订阅级锁内串行完成，结论确定可复现。
- 补差单是**独立单据**，状态机与发票同口径：
  `OPEN -> PAID / PAYMENT_FAILED ->(retry) ... -> MAX_RETRIES_EXCEEDED`，`PAID -> REFUNDED`。
  通道幂等键 `ADJPAY:{adjustmentId}:{attemptNo}`，最大尝试 3 次，失败原因可区分。
- **入账口径与支付/退款一致**：补差单只在收款成功后登一笔 `CHARGE`
  （`refType=ADJUSTMENT`），失败尝试不入账。因此：
  - 账期已 `PAID`：补差单独收款，原支付**不会被重复扣款**；
  - 账期 `PAYMENT_FAILED / MAX_RETRIES_EXCEEDED`：补差独立成单、可继续收款，不漏计、不静默丢单；
  - 账期已 `VOID`：仍可接收迟到量并生成补差，补差单独收款。
- **作废联动**：对一张已支付发票退款作废时，系统同时退回该账期所有已收款的补差单
  （各登一笔 `REFUND` 并置 `REFUNDED`），账期全部关闭后
  `SUM(CHARGE) = SUM(CREDIT) + SUM(REFUND)` 依然成立。加锁顺序固定
  `sub -> inv / sub -> adj`，避免交叉死锁。

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
   PAID ──refund──▶ VOID（终态，登记 REFUND；联动退回该账期已收款补差）
```

补差单（Adjustment）状态机与发票对称：

```
   (发票已存在 + 迟到用量) ──settle──▶ OPEN ──pay 成功──▶ PAID（终态，登 ADJUSTMENT/CHARGE）
                                          │ pay/retry 失败
                                          ▼
                                   PAYMENT_FAILED ──retry──▶ ... ──3 次失败──▶ MAX_RETRIES_EXCEEDED
   PAID ──refund（或随原发票作废联动）──▶ REFUNDED（终态，登 ADJUSTMENT/REFUND）
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
| 结算后补差 | `(订阅, 周期, batchId)` 唯一；无 batchId 时 `(订阅, 周期, 内容指纹)` 兜底 + 订阅锁 | 同一份补差单、同一 delta 与单号，`created=false`；仅整期确无净增才 delta=0 不落单 |
| 补差收款 | `ADJPAY:补差单:尝试序号` + 单据级锁/状态守卫 | 同键同结果同流水号，终态重复调用不产生新交易 |
| 通道扣款 | `PAY:发票:尝试序号` | 同键同结果同流水号 |
| 支付/重试/退款 | 发票级锁 + 状态机守卫 | 终态上重复调用不产生新交易 |

## 5. 代码结构

```
domain/   Money、Plan、MeterTier、Subscription(Version)、UsageEvent、
          Invoice(LineItem)、Adjustment（结算后补差单）、PaymentAttempt、LedgerEntry
          （含 refType：INVOICE/ADJUSTMENT）与各状态/原因枚举
repo/     仓储接口 + ConcurrentHashMap/CopyOnWriteArrayList 内存实现 + 按键锁
service/  PeriodService（周期）、PricingService（阶梯计价）、
          BillingCalculator（整期费用计算，开票与补差共用同一口径）、
          UsageIngestionService（采集）、SubscriptionService（变更）、
          InvoiceService（开票，委托 BillingCalculator）、
          AdjustmentService（结算后补差的触发/幂等/收款/退款）、
          PaymentGateway/SimulatedPaymentGateway、
          PaymentService（发票状态机/重试/退款，退款联动退补差）、LedgerService（守恒）
web/      REST 控制器（含 AdjustmentController）、请求/视图 DTO、统一异常处理（400/404/409）
config/   Clock Bean（测试可替换为固定时钟）、套餐种子
tests/    UsageIngestionTest、PlanChangeProrationTest、ConcurrentInvoiceTest、
          PaymentRetryTest、BillingEndToEndApiTest、
          LateUsageAdjustmentTest、AdjustmentEndToEndApiTest、
          NullBatchIdempotencyTest（不传 batchId 时的重放/并发/重试/零值/回读/累计守恒）
```

### 对原有入口的兼容范围

- 用量上报 `/api/usage-events`、开票、支付/重试/退款、查询、账本接口的**路径、请求体与
  返回结构均未改变**；迟到事件只是“被正常接收并计入对应账期”，不影响这些入口的既有结论。
- 补差能力是新增的独立入口（`/api/subscriptions/{id}/adjustments`、
  `/api/adjustments/{id}/*`）与新增的独立单据/存储，不修改原发票数据。
- 唯一的内部增强：发票退款时若该账期存在已收款补差会联动退回（使账本继续守恒）；
  无补差时行为与之前完全一致。原 14 个测试全部保持通过。

关键日志覆盖所有入口的**关键输入与判定依据**（ACCEPTED/DUPLICATE/REJECTED 原因、
聚合总量、发票各行、通道扣款结果、状态推进、入账后守恒校验），可用
`mvn test` 输出或运行期日志审计每一步结论。

## 6. 已知边界（演示版）

- 全部状态（含补差单）在内存，重启即清空；生产应替换为数据库事务/行锁，
  幂等键与 `(订阅,周期)` 唯一约束、补差 `(订阅,周期,批次)` 唯一约束下沉到存储层。
- 补差只处理**非负补收**（迟到用量只增不减）。若整期重算总额低于此前已计金额
  （例如账期内回填了一个会降价的套餐变更点），补差显式报错而不记负账，补退请走退款流程。
- 补差的触发为显式调用（上报后由调用方/批处理触发结算）；本演示未做“上报即自动补差”。
- 计费周期固定 UTC 自然月、按天分摊；含税、多币种、预付款余额、
  跨月退款冲销等未纳入本演示。
