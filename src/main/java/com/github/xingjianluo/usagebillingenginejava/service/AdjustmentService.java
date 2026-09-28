package com.github.xingjianluo.usagebillingenginejava.service;

import com.github.xingjianluo.usagebillingenginejava.domain.Adjustment;
import com.github.xingjianluo.usagebillingenginejava.domain.AdjustmentStatus;
import com.github.xingjianluo.usagebillingenginejava.domain.BillingPeriod;
import com.github.xingjianluo.usagebillingenginejava.domain.Invoice;
import com.github.xingjianluo.usagebillingenginejava.domain.LedgerEntry;
import com.github.xingjianluo.usagebillingenginejava.domain.LedgerEntryType;
import com.github.xingjianluo.usagebillingenginejava.domain.LedgerRefType;
import com.github.xingjianluo.usagebillingenginejava.domain.Money;
import com.github.xingjianluo.usagebillingenginejava.domain.PaymentFailureReason;
import com.github.xingjianluo.usagebillingenginejava.domain.Subscription;
import com.github.xingjianluo.usagebillingenginejava.repo.AdjustmentRepository;
import com.github.xingjianluo.usagebillingenginejava.repo.InvoiceRepository;
import com.github.xingjianluo.usagebillingenginejava.repo.LedgerRepository;
import com.github.xingjianluo.usagebillingenginejava.repo.RepositoryLocks;
import com.github.xingjianluo.usagebillingenginejava.repo.SubscriptionRepository;
import com.github.xingjianluo.usagebillingenginejava.service.dto.AdjustmentPaymentResult;
import com.github.xingjianluo.usagebillingenginejava.service.dto.AdjustmentResult;
import com.github.xingjianluo.usagebillingenginejava.service.dto.BillingCalculation;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 结算后补差服务。
 *
 * 触发条件：某账期的发票已经开出（无论 OPEN / PAYMENT_FAILED / PAID / VOID），
 * 之后又有发生时间落在该账期内的用量补报进来（上报入口不变，迟到事件不会被丢弃），
 * 调用 {@link #settle} 即对该账期产出一份独立补差单。
 *
 * 计算口径（与正常出账完全相同，复用 {@link BillingCalculator}）：
 *   recalculatedTotal = 用当前全部用量（含迟到部分）对整期重新算出的应计总额；
 *   previouslyBilled  = 原发票净额 + 该账期此前所有补差净额；
 *   delta             = recalculatedTotal − previouslyBilled。
 * 迟到量把整期累计推进更高阶梯档时，按整期重算（不是只对新事件单独套一次价）。
 *
 * 不改写原账单：补差是新单据，原发票编号/明细/金额永不变化。
 *
 * 幂等：批次键 (subscriptionId, period, batchId) 唯一 + 订阅级锁；
 *   - 同一批输入重放/并发：batchId 命中既有补差单，created=false 返回同一份，不重复入账；
 *   - 无新增用量或重算后总额无变化：delta=0，明确不产生补差单、不产生费用。
 *
 * 收款/退款口径与发票一致：补差单只在收款成功后登一笔 CHARGE；失败可区分原因并可重试，
 * 达到上限进入终态不静默丢单；PAID 补差可退款登 REFUND。
 */
@Service
public class AdjustmentService {

    private static final Logger log = LoggerFactory.getLogger(AdjustmentService.class);

    /** 与发票一致的最大收款尝试次数（首次 pay + 后续 retry）。 */
    static final int MAX_ATTEMPTS = 3;

    private final AdjustmentRepository adjustmentRepository;
    private final InvoiceRepository invoiceRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final LedgerRepository ledgerRepository;
    private final BillingCalculator billingCalculator;
    private final PaymentGateway paymentGateway;
    private final LedgerService ledgerService;
    private final RepositoryLocks locks;
    private final Clock clock;

    public AdjustmentService(AdjustmentRepository adjustmentRepository,
                             InvoiceRepository invoiceRepository,
                             SubscriptionRepository subscriptionRepository,
                             LedgerRepository ledgerRepository,
                             BillingCalculator billingCalculator,
                             PaymentGateway paymentGateway,
                             LedgerService ledgerService,
                             RepositoryLocks locks, Clock clock) {
        this.adjustmentRepository = adjustmentRepository;
        this.invoiceRepository = invoiceRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.ledgerRepository = ledgerRepository;
        this.billingCalculator = billingCalculator;
        this.paymentGateway = paymentGateway;
        this.ledgerService = ledgerService;
        this.locks = locks;
        this.clock = clock;
    }

    /**
     * 对某订阅的账期触发一次结算后补差。
     * @param batchId 上报方提供的批次幂等键，可空；非空时同键重放/并发只产生一份补差单。
     */
    public AdjustmentResult settle(String subscriptionId, BillingPeriod period, String batchId) {
        log.info("补差结算输入: subscriptionId={}, period=[{} {}), batchId={}",
                subscriptionId, period.start(), period.end(), batchId);
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalArgumentException("订阅不存在: " + subscriptionId));

        ReentrantLock lock = locks.forKey("sub:" + subscriptionId);
        lock.lock();
        try {
            // 幂等：同批次键已结算过，直接回读同一份补差单。
            if (batchId != null) {
                var existing = adjustmentRepository.findByBatch(subscriptionId, period, batchId);
                if (existing.isPresent()) {
                    Adjustment a = existing.get();
                    log.info("补差批次已存在，幂等返回同一份: adjustmentId={}, delta={}, status={}"
                                    + "（不重复生成/入账）", a.id(), a.delta(), a.status());
                    return toResult(a, false, "批次键 " + batchId + " 已结算，返回既有补差单");
                }
            }

            Invoice invoice = invoiceRepository.findBySubscriptionAndPeriod(subscriptionId, period)
                    .orElseThrow(() -> new IllegalStateException(
                            "该账期尚未出账，不能触发结算后补差，请先开票: " + subscriptionId
                                    + " @ " + period.start()));

            BillingCalculation calc = billingCalculator.calculate(subscription, period);
            Money recalculatedTotal = calc.netTotal();

            Money previouslyBilled = invoice.total();
            List<Adjustment> prior =
                    adjustmentRepository.findBySubscriptionAndPeriod(subscriptionId, period);
            for (Adjustment a : prior) {
                previouslyBilled = previouslyBilled.plus(a.delta());
            }

            Money delta = recalculatedTotal.minus(previouslyBilled);
            String basisSummary = summarize(calc, invoice, recalculatedTotal, previouslyBilled);
            log.info("补差判定依据: subscriptionId={}, period={}, 原发票净额={}, 历史补差数={}, "
                            + "重算应计总额={}, 此前已计={}, delta={}",
                    subscriptionId, period.start(), invoice.total(), prior.size(),
                    recalculatedTotal, previouslyBilled, delta);

            // 用量只增不减，迟到补报不应让整期应计下降；若出现（如套餐版本回填变更），
            // 属于另一类“补退”流程，显式报错而不是静默记错账。
            if (delta.isNegative()) {
                log.warn("整期重算总额低于此前已计金额（疑似账期内套餐回填变更），"
                        + "补差只处理补收，拒绝自动记负: delta={}", delta);
                throw new IllegalStateException(
                        "整期重算应计总额低于此前已计金额，补差流程仅处理非负补收，delta="
                                + delta + "；补退请走退款流程");
            }
            if (delta.equals(Money.zero())) {
                log.info("补差金额为 0，不产生补差单、不入账: subscriptionId={}, period={}, "
                                + "依据=重算总额 {} 等于此前已计 {}",
                        subscriptionId, period.start(), recalculatedTotal, previouslyBilled);
                return new AdjustmentResult(null, subscriptionId, invoice.id(),
                        period.start().toString(), period.end().toString(), prior.size(), batchId,
                        Money.zero(), recalculatedTotal, previouslyBilled, null, false,
                        calc.basisEventIds(), basisSummary,
                        "重算后应计总额未变化，补差为 0，不产生费用");
            }

            int sequenceNo = prior.size() + 1;
            Adjustment adjustment = new Adjustment(UUID.randomUUID().toString(), subscriptionId,
                    period, invoice.id(), sequenceNo, batchId, delta, recalculatedTotal,
                    previouslyBilled, calc.basisEventIds(), basisSummary, Instant.now(clock));

            Adjustment concurrent =
                    adjustmentRepository.insertIfAbsent(adjustment).orElse(null);
            if (concurrent != null) {
                log.info("并发补差落败，回读既有补差单: adjustmentId={}, delta={}",
                        concurrent.id(), concurrent.delta());
                return toResult(concurrent, false, "并发批次已被另一请求结算，返回同一份补差单");
            }
            log.info("补差单已生成: adjustmentId={}, invoiceId={}, seq={}, batchId={}, delta={}, "
                            + "重算总额={}, 依据事件数={}（原发票不改写）",
                    adjustment.id(), invoice.id(), sequenceNo, batchId, delta,
                    recalculatedTotal, calc.basisEventIds().size());
            return toResult(adjustment, true, "检测到迟到用量，已按整期重算生成补差单");
        } finally {
            lock.unlock();
        }
    }

    public Adjustment getAdjustment(String adjustmentId) {
        return adjustmentRepository.findById(adjustmentId)
                .orElseThrow(() -> new IllegalArgumentException("补差单不存在: " + adjustmentId));
    }

    // ---- 收款 / 重试 / 退款（状态机与通道口径同 PaymentService）----

    public AdjustmentPaymentResult pay(String adjustmentId) {
        return attempt(adjustmentId, false);
    }

    public AdjustmentPaymentResult retry(String adjustmentId) {
        return attempt(adjustmentId, true);
    }

    private AdjustmentPaymentResult attempt(String adjustmentId, boolean isRetry) {
        Adjustment adjustment = getAdjustment(adjustmentId);
        ReentrantLock lock = locks.forKey("adj:" + adjustmentId);
        lock.lock();
        try {
            log.info("补差收款输入: adjustmentId={}, action={}, status={}, attemptCount={}, delta={}",
                    adjustmentId, isRetry ? "RETRY" : "PAY", adjustment.status(),
                    adjustment.attemptCount(), adjustment.delta());

            if (adjustment.status() == AdjustmentStatus.PAID) {
                log.info("补差单已支付，幂等返回成功（不重复扣款/入账）: adjustmentId={}", adjustmentId);
                return snapshot(adjustment, true, PaymentFailureReason.NONE);
            }
            if (adjustment.status() == AdjustmentStatus.REFUNDED
                    || adjustment.status() == AdjustmentStatus.MAX_RETRIES_EXCEEDED) {
                log.info("补差单处于终态 {}，拒绝再次交易: adjustmentId={}, lastReason={}",
                        adjustment.status(), adjustmentId, adjustment.lastFailureReason());
                return snapshot(adjustment, false, adjustment.lastFailureReason());
            }
            if (isRetry && adjustment.status() != AdjustmentStatus.PAYMENT_FAILED) {
                log.info("RETRY 仅允许在 PAYMENT_FAILED 状态发起，当前={}，本次不扣款",
                        adjustment.status());
                return snapshot(adjustment, false, adjustment.lastFailureReason());
            }
            if (!isRetry && adjustment.status() != AdjustmentStatus.OPEN) {
                log.info("PAY 仅允许在 OPEN 状态发起，当前={}，请走重试入口，本次不扣款",
                        adjustment.status());
                return snapshot(adjustment, false, adjustment.lastFailureReason());
            }

            if (isRetry) {
                adjustment.transitionTo(AdjustmentStatus.RETRYING, Instant.now(clock));
            }
            int attemptNo = adjustment.attemptCount() + 1;
            String key = "ADJPAY:" + adjustmentId + ":" + attemptNo;
            var response = paymentGateway.charge(
                    adjustment.subscriptionId(), adjustment.delta(), key);

            adjustment.recordPaymentResult(response.success(), response.failureReason(),
                    response.reference(), Instant.now(clock));
            if (response.success()) {
                adjustment.transitionTo(AdjustmentStatus.PAID, Instant.now(clock));
                ledgerService.post(adjustment.subscriptionId(), LedgerEntryType.CHARGE,
                        adjustment.delta(), adjustment.id(), LedgerRefType.ADJUSTMENT,
                        "补差单 " + adjustment.id() + " 补收（账期 "
                                + adjustment.period().start() + " 整期重算净增）");
                log.info("补差收款成功: adjustmentId={}, attemptNo={}, gatewayRef={}, 状态=PAID, "
                                + "入账后守恒={}", adjustmentId, attemptNo, response.reference(),
                        ledgerService.isConserved(adjustment.subscriptionId()));
                return snapshot(adjustment, true, PaymentFailureReason.NONE);
            }

            AdjustmentStatus next = adjustment.attemptCount() >= MAX_ATTEMPTS
                    ? AdjustmentStatus.MAX_RETRIES_EXCEEDED : AdjustmentStatus.PAYMENT_FAILED;
            adjustment.transitionTo(next, Instant.now(clock));
            log.info("补差收款失败: adjustmentId={}, attemptNo={}, 可区分原因={}, 新状态={}, 未入账",
                    adjustmentId, attemptNo, response.failureReason(), next);
            return snapshot(adjustment, false, response.failureReason());
        } finally {
            lock.unlock();
        }
    }

    /** 退还已收款的补差单（全额退 delta），登一笔 REFUND，状态置 REFUNDED。 */
    public AdjustmentPaymentResult refund(String adjustmentId) {
        Adjustment adjustment = getAdjustment(adjustmentId);
        ReentrantLock lock = locks.forKey("adj:" + adjustmentId);
        lock.lock();
        try {
            return doRefund(adjustment);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 供 {@link PaymentService} 在原发票退款（作废）时联动调用：在<b>持有订阅锁、未持有该补差单
     * 锁</b>的前提下退掉该账期所有已支付补差单，保证账期作废后账本仍守恒。
     * 为避免重入死锁，这里自行获取各补差单锁（与 refund 入口的加锁顺序一致：sub -> adj）。
     */
    public void refundPaidForPeriodHoldingSubLock(String subscriptionId, BillingPeriod period) {
        for (Adjustment a : adjustmentRepository.findBySubscriptionAndPeriod(subscriptionId, period)) {
            if (a.status() != AdjustmentStatus.PAID) {
                continue;
            }
            ReentrantLock adjLock = locks.forKey("adj:" + a.id());
            adjLock.lock();
            try {
                if (a.status() == AdjustmentStatus.PAID) {
                    doRefund(a);
                }
            } finally {
                adjLock.unlock();
            }
        }
    }

    private AdjustmentPaymentResult doRefund(Adjustment adjustment) {
        log.info("补差退款输入: adjustmentId={}, status={}", adjustment.id(), adjustment.status());
        if (adjustment.status() == AdjustmentStatus.REFUNDED) {
            log.info("补差单已退款，幂等返回: adjustmentId={}", adjustment.id());
            return snapshot(adjustment, true, PaymentFailureReason.NONE);
        }
        if (adjustment.status() != AdjustmentStatus.PAID) {
            throw new IllegalStateException(
                    "仅 PAID 补差单可退款，当前状态=" + adjustment.status());
        }
        String refundRef = "GW-REF-" + adjustment.id();
        ledgerService.post(adjustment.subscriptionId(), LedgerEntryType.REFUND,
                adjustment.delta(), adjustment.id(), LedgerRefType.ADJUSTMENT,
                "作废补差单 " + adjustment.id() + " 通道退款 " + refundRef);
        adjustment.transitionTo(AdjustmentStatus.REFUNDED, Instant.now(clock));
        log.info("补差退款完成: adjustmentId={}, refundAmount={}, 状态=REFUNDED, 入账后守恒={}",
                adjustment.id(), adjustment.delta(),
                ledgerService.isConserved(adjustment.subscriptionId()));
        return snapshot(adjustment, true, PaymentFailureReason.NONE);
    }

    private String summarize(BillingCalculation calc, Invoice invoice, Money recalculated,
                             Money previouslyBilled) {
        return "整期重算应计=" + recalculated + "（原发票 " + invoice.id()
                + " 净额=" + invoice.total() + " + 历史补差="
                + previouslyBilled.minus(invoice.total()) + "），各套餐片累计="
                + calc.meterTotalsBySegment() + "，依据事件=" + calc.basisEventIds();
    }

    private AdjustmentResult toResult(Adjustment a, boolean created, String reason) {
        return new AdjustmentResult(a.id(), a.subscriptionId(), a.invoiceId(),
                a.period().start().toString(), a.period().end().toString(), a.sequenceNo(),
                a.batchId(), a.delta(), a.recalculatedTotal(), a.previouslyBilled(),
                a.status().name(), created, a.basisEventIds(), a.basisSummary(), reason);
    }

    private AdjustmentPaymentResult snapshot(Adjustment a, boolean success,
                                             PaymentFailureReason reason) {
        Money charge = Money.zero();
        Money refund = Money.zero();
        for (LedgerEntry e : ledgerRepository.findByInvoice(a.id())) {
            if (e.refType() == LedgerRefType.ADJUSTMENT && e.type() == LedgerEntryType.CHARGE) {
                charge = charge.plus(e.amount());
            }
            if (e.refType() == LedgerRefType.ADJUSTMENT && e.type() == LedgerEntryType.REFUND) {
                refund = refund.plus(e.amount());
            }
        }
        return new AdjustmentPaymentResult(a.id(), success, reason, a.attemptCount(),
                a.status(), a.delta(), charge, refund);
    }
}
