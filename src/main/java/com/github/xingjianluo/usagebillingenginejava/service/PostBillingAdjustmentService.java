package com.github.xingjianluo.usagebillingenginejava.service;

import com.github.xingjianluo.usagebillingenginejava.domain.Adjustment;
import com.github.xingjianluo.usagebillingenginejava.domain.AdjustmentStatus;
import com.github.xingjianluo.usagebillingenginejava.domain.BillingPeriod;
import com.github.xingjianluo.usagebillingenginejava.domain.Invoice;
import com.github.xingjianluo.usagebillingenginejava.domain.InvoiceLineItem;
import com.github.xingjianluo.usagebillingenginejava.domain.InvoiceStatus;
import com.github.xingjianluo.usagebillingenginejava.domain.LedgerEntryType;
import com.github.xingjianluo.usagebillingenginejava.domain.Money;
import com.github.xingjianluo.usagebillingenginejava.domain.PaymentFailureReason;
import com.github.xingjianluo.usagebillingenginejava.domain.Subscription;
import com.github.xingjianluo.usagebillingenginejava.repo.AdjustmentRepository;
import com.github.xingjianluo.usagebillingenginejava.repo.InvoiceRepository;
import com.github.xingjianluo.usagebillingenginejava.repo.RepositoryLocks;
import com.github.xingjianluo.usagebillingenginejava.repo.SubscriptionRepository;
import com.github.xingjianluo.usagebillingenginejava.service.dto.AdjustmentResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 结算后补差服务。
 *
 * 触发：账期已经出过账单（OPEN / PAYMENT_FAILED / PAID / VOID 任意状态），之后又有
 * occurredAt 落在该账期内的用量事件被正常接收（采集入口不变、不丢单），调用本服务对该
 * 账期做一次补差结算。
 *
 * 口径：
 *   amount = 用当前能看到的全部用量（含迟到部分）按 {@link PeriodBillingCalculator} 对整期
 *            重算出的应计总额 − 该账期此前已经计过的金额（原账单净额 + 既往补差金额之和）。
 * 跨档提价由“整期总量重算阶梯”自然得出，绝不只对新事件单独套一次价；口径与正常出账、
 * 周期内变更、按天分摊完全一致。迟到用量只可能让总额不减，故 amount 恒非负；无变化为 0。
 *
 * 幂等：补差内容键 = SHA-256(该账期当前可见全部事件 id 的有序集合)。同一批输入无论重放、
 * 并发还是重试，看到的事件集合相同 -> 内容键相同 -> 唯一约束保证只生成一张补差单；
 * 来了真正的新用量（集合变化）才产生下一张补差单。
 *
 * 账本：补差单与支付/退款同口径。补差金额为 0 不产生任何分录；正数补差仅在收款成功时
 * 登记一笔 CHARGE（关联原账单号），随原账单退款时由 PaymentService 冲回 REFUND。
 * 原账单未支付时补差不扣款，等原账单支付成功或显式收取补差时才真正收款，杜绝重复扣款。
 */
@Service
public class PostBillingAdjustmentService {

    private static final Logger log = LoggerFactory.getLogger(PostBillingAdjustmentService.class);

    private final AdjustmentRepository adjustmentRepository;
    private final InvoiceRepository invoiceRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final com.github.xingjianluo.usagebillingenginejava.repo.UsageEventRepository
            usageEventRepository;
    private final PeriodService periodService;
    private final PeriodBillingCalculator billingCalculator;
    private final PaymentGateway paymentGateway;
    private final LedgerService ledgerService;
    private final RepositoryLocks locks;
    private final Clock clock;

    public PostBillingAdjustmentService(AdjustmentRepository adjustmentRepository,
                                        InvoiceRepository invoiceRepository,
                                        SubscriptionRepository subscriptionRepository,
                                        com.github.xingjianluo.usagebillingenginejava.repo.UsageEventRepository
                                                usageEventRepository,
                                        PeriodService periodService,
                                        PeriodBillingCalculator billingCalculator,
                                        PaymentGateway paymentGateway,
                                        LedgerService ledgerService,
                                        RepositoryLocks locks, Clock clock) {
        this.adjustmentRepository = adjustmentRepository;
        this.invoiceRepository = invoiceRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.usageEventRepository = usageEventRepository;
        this.periodService = periodService;
        this.billingCalculator = billingCalculator;
        this.paymentGateway = paymentGateway;
        this.ledgerService = ledgerService;
        this.locks = locks;
        this.clock = clock;
    }

    /**
     * 对一个已出账账期做补差结算。无原账单视为非法触发（该走正常开票入口）。
     */
    public AdjustmentResult settleLateUsage(String subscriptionId, BillingPeriod period) {
        if (period == null) {
            period = periodService.currentPeriod();
        }
        log.info("补差结算输入: subscriptionId={}, period=[{} {})", subscriptionId,
                period.start(), period.end());
        final BillingPeriod p = period;

        // 先取订阅锁（串行化同账期补差/开票），补差收款再取原账单锁（与支付互斥）；
        // 加锁方向固定为 sub -> inv，与开票、支付不形成交叉死锁。
        Subscription subscription;
        Invoice invoice;
        ReentrantLock subLock = locks.forKey("sub:" + subscriptionId);
        subLock.lock();
        try {
            subscription = subscriptionRepository.findById(subscriptionId)
                    .orElseThrow(() -> new IllegalArgumentException("订阅不存在: " + subscriptionId));
            invoice = invoiceRepository.findBySubscriptionAndPeriod(subscriptionId, p)
                    .orElse(null);
            if (invoice == null) {
                throw new IllegalStateException(
                        "该账期尚未出账，不能补差，请先开票: " + subscriptionId + " " + p.start());
            }
            ReentrantLock invLock = locks.forKey("inv:" + invoice.id());
            invLock.lock();
            try {
                return doSettle(subscription, invoice, p);
            } finally {
                invLock.unlock();
            }
        } finally {
            subLock.unlock();
        }
    }

    private AdjustmentResult doSettle(Subscription subscription, Invoice invoice,
                                      BillingPeriod period) {
        // 1) 当前账期可见的全部事件 id（有序、去重），既是内容键也是补差依据。
        Instant periodStart = periodService.startInstant(period);
        Instant periodEnd = periodService.endInstant(period);
        List<String> basisEventIds = usageEventRepository
                .findInWindow(subscription.id(), periodStart, periodEnd).stream()
                .map(com.github.xingjianluo.usagebillingenginejava.domain.UsageEvent::eventId)
                .sorted()
                .toList();
        String contentKey = contentKey(basisEventIds);

        // 2) 同内容幂等：重放/并发直接回读既有补差单，绝不重复生成或重复入账。
        var existing = adjustmentRepository.findByContentKey(
                subscription.id(), period, contentKey);
        if (existing.isPresent()) {
            Adjustment a = existing.get();
            log.info("补差内容键已存在，幂等返回: adjustmentId={}, status={}, amount={}（不重复补差/入账）",
                    a.id(), a.status(), a.amount());
            return toResult(a, invoice, false, "同批输入已处理，返回既有补差单");
        }

        // 3) 用全部可见用量对整期重算应计总额（与正常开票同一计算器、同一口径）。
        List<InvoiceLineItem> recalcLines =
                billingCalculator.buildLines(subscription, period, "RECALC");
        Money recalculatedTotal = billingCalculator.netTotal(recalcLines);

        // 4) 该账期此前已经计过的金额 = 原账单净额 + 既往补差金额之和。
        Money previouslyBilled = invoice.total();
        for (Adjustment prior : adjustmentRepository
                .findBySubscriptionAndPeriod(subscription.id(), period)) {
            previouslyBilled = previouslyBilled.plus(prior.amount());
        }
        Money amount = recalculatedTotal.minus(previouslyBilled);
        if (amount.isNegative()) {
            // 迟到用量只会增不会减；负值说明出现了口径外的变更（如回填降级），明确报错而非静默错账。
            throw new IllegalStateException("重算总额低于已计金额，拒绝生成负补差: recalculated="
                    + recalculatedTotal + ", previouslyBilled=" + previouslyBilled);
        }

        int sequence = adjustmentRepository
                .findBySubscriptionAndPeriod(subscription.id(), period).size() + 1;
        Adjustment adjustment = new Adjustment(UUID.randomUUID().toString(), subscription.id(),
                period, invoice.id(), contentKey, amount, basisEventIds, sequence,
                Instant.now(clock));

        Adjustment concurrent = adjustmentRepository.insertIfAbsent(adjustment).orElse(null);
        if (concurrent != null) {
            log.info("并发补差落败，采用已插入补差单: adjustmentId={}, amount={}",
                    concurrent.id(), concurrent.amount());
            return toResult(concurrent, invoice, false, "并发触发，采用另一请求生成的补差单");
        }

        log.info("补差单已生成: adjustmentId={}, invoiceId={}, seq={}, 依据事件数={}, "
                        + "重算总额={}, 此前已计={}, 净增={}",
                adjustment.id(), invoice.id(), sequence, basisEventIds.size(),
                recalculatedTotal, previouslyBilled, amount);

        // 5) 收款与入账口径：
        //    - 金额为 0：无费用，直接 PAID，不产生任何分录；
        //    - 原账单已支付（PAID）：立即就净增额向通道收款一次（绝不重扣原账单金额），
        //      成功才登记 CHARGE；失败留 PAYMENT_FAILED，可重试，不静默丢单；
        //    - 原账单 OPEN / PAYMENT_FAILED / VOID：补差保持 OPEN，不提前扣款；
        //      原账单日后支付成功时（或显式收取补差时）再收款入账。
        if (Money.zero().equals(amount)) {
            adjustment.transitionTo(AdjustmentStatus.PAID, Instant.now(clock));
            log.info("补差为零，不产生费用与分录: adjustmentId={}", adjustment.id());
        } else if (invoice.status() == InvoiceStatus.PAID) {
            collect(adjustment, accountOf(adjustment));
        } else {
            log.info("原账单尚未收款（status={}），补差挂账 OPEN 待收，不提前扣款: adjustmentId={}",
                    invoice.status(), adjustment.id());
        }
        return toResult(adjustment, invoice, true,
                Money.zero().equals(amount) ? "重算总额无变化，补差为零" : "已按整期重算净增生成补差");
    }

    /**
     * 就补差净增额向通道收款；通道幂等键 ADJ:{adjustmentId}:{attemptNo}，
     * 重放不会重复扣款。仅成功时登记一笔 CHARGE，与发票支付入账口径一致。
     */
    private PaymentFailureReason collect(Adjustment adjustment, String accountId) {
        int attemptNo = adjustment.attemptCount() + 1;
        String key = "ADJ:" + adjustment.id() + ":" + attemptNo;
        var response = paymentGateway.charge(accountId, adjustment.amount(), key);
        adjustment.recordPaymentResult(response.success(), response.failureReason(),
                response.reference(), Instant.now(clock));
        if (response.success()) {
            adjustment.transitionTo(AdjustmentStatus.PAID, Instant.now(clock));
            ledgerService.post(adjustment.subscriptionId(), LedgerEntryType.CHARGE,
                    adjustment.amount(), adjustment.invoiceId(),
                    "补差单 " + adjustment.id() + " 结算后迟到用量净增收款（原账单 "
                            + adjustment.invoiceId() + "，依据 "
                            + adjustment.basisEventIds().size() + " 个事件整期重算）");
            log.info("补差收款成功并入账: adjustmentId={}, amount={}, gatewayRef={}, 守恒={}",
                    adjustment.id(), adjustment.amount(), response.reference(),
                    ledgerService.isConserved(adjustment.subscriptionId()));
            return PaymentFailureReason.NONE;
        }
        adjustment.transitionTo(AdjustmentStatus.PAYMENT_FAILED, Instant.now(clock));
        log.info("补差收款失败（未入账，可重试）: adjustmentId={}, amount={}, 原因={}",
                adjustment.id(), adjustment.amount(), response.failureReason());
        return response.failureReason();
    }

    /**
     * 收取一张挂账/收款失败的补差单（原账单仍未支付，或已支付账期的补差首次收款失败）。
     * 供显式入口与原账单支付成功后的联动收款复用；持原账单锁由调用方保证。
     */
    public AdjustmentResult collectAdjustment(String adjustmentId) {
        Adjustment adjustment = adjustmentRepository.findById(adjustmentId)
                .orElseThrow(() -> new IllegalArgumentException("补差单不存在: " + adjustmentId));
        ReentrantLock lock = locks.forKey("inv:" + adjustment.invoiceId());
        lock.lock();
        try {
            Invoice invoice = invoiceRepository.findById(adjustment.invoiceId())
                    .orElseThrow(() -> new IllegalStateException("原账单缺失: " + adjustment.invoiceId()));
            Subscription subscription = subscriptionRepository.findById(adjustment.subscriptionId())
                    .orElseThrow(() -> new IllegalStateException("订阅缺失: " + adjustment.subscriptionId()));
            log.info("补差收款输入: adjustmentId={}, status={}, attemptCount={}, amount={}",
                    adjustmentId, adjustment.status(), adjustment.attemptCount(),
                    adjustment.amount());
            if (adjustment.status() == AdjustmentStatus.PAID) {
                log.info("补差已收款，幂等返回（不重复扣款/入账）: adjustmentId={}", adjustmentId);
                return toResult(adjustment, invoice, false, "补差已支付，幂等返回");
            }
            if (adjustment.status() == AdjustmentStatus.VOID) {
                log.info("补差已随原账单退款冲回，拒绝再次收款: adjustmentId={}", adjustmentId);
                return toResult(adjustment, invoice, false, "补差已作废");
            }
            if (Money.zero().equals(adjustment.amount())) {
                adjustment.transitionTo(AdjustmentStatus.PAID, Instant.now(clock));
                return toResult(adjustment, invoice, false, "补差为零");
            }
            PaymentFailureReason reason = collect(adjustment, accountOf(adjustment));
            return toResult(adjustment, invoice, false,
                    reason == PaymentFailureReason.NONE ? "补差收款成功" : "补差收款失败: " + reason);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 原账单支付成功后联动收款：把该账期所有仍挂账的补差依次收取（不新增补差，只收款）。
     * 必须在持有原账单锁的情况下调用（由 PaymentService 保证）。
     */
    public void collectOpenAdjustmentsForInvoice(Invoice invoice) {
        List<Adjustment> open = adjustmentRepository
                .findBySubscriptionAndPeriod(invoice.subscriptionId(), invoice.period()).stream()
                .filter(a -> a.invoiceId().equals(invoice.id()))
                .filter(a -> a.status() == AdjustmentStatus.OPEN
                        || a.status() == AdjustmentStatus.PAYMENT_FAILED)
                .toList();
        if (open.isEmpty()) {
            return;
        }
        Subscription subscription = subscriptionRepository.findById(invoice.subscriptionId()).orElseThrow();
        for (Adjustment a : open) {
            log.info("原账单支付成功，联动收取挂账补差: adjustmentId={}, amount={}", a.id(), a.amount());
            collect(a, accountOf(a));
        }
    }

    /**
     * 原账单退款时联动冲回：所有已收款（PAID 且正数）补差各登一笔 REFUND 并置 VOID，
     * 使账本仍满足 CHARGE = CREDIT + REFUND。必须在持有原账单锁时调用。
     * 返回冲回总额。
     */
    public Money voidPaidAdjustmentsForInvoice(Invoice invoice) {
        Money refunded = Money.zero();
        for (Adjustment a : adjustmentRepository
                .findBySubscriptionAndPeriod(invoice.subscriptionId(), invoice.period())) {
            if (a.invoiceId().equals(invoice.id())
                    && a.status() == AdjustmentStatus.PAID
                    && !Money.zero().equals(a.amount())) {
                ledgerService.post(a.subscriptionId(), LedgerEntryType.REFUND, a.amount(),
                        a.invoiceId(),
                        "补差单 " + a.id() + " 随原账单 " + invoice.id() + " 作废退款");
                a.transitionTo(AdjustmentStatus.VOID, Instant.now(clock));
                refunded = refunded.plus(a.amount());
                log.info("补差随退款冲回: adjustmentId={}, refund={}", a.id(), a.amount());
            }
        }
        return refunded;
    }

    /** 收款账户口径与 PaymentService 保持一致：本地模拟账户号即订阅号。 */
    private String accountOf(Adjustment adjustment) {
        return adjustment.subscriptionId();
    }

    public Adjustment getAdjustment(String adjustmentId) {
        return adjustmentRepository.findById(adjustmentId)
                .orElseThrow(() -> new IllegalArgumentException("补差单不存在: " + adjustmentId));
    }

    private List<com.github.xingjianluo.usagebillingenginejava.domain.UsageEvent>
            billingCalculatorEvents(String subscriptionId, Instant from, Instant to) {
        return billingCalculatorEventRepository.findInWindow(subscriptionId, from, to);
    }

    private final com.github.xingjianluo.usagebillingenginejava.repo.UsageEventRepository
            billingCalculatorEventRepository = null;

    private static String contentKey(List<String> sortedEventIds) {
        String joined = String.join(",", sortedEventIds);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(joined.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private AdjustmentResult toResult(Adjustment a, Invoice invoice, boolean created,
                                      String reason) {
        Money previouslyBilled = invoice.total();
        for (Adjustment prior : adjustmentRepository
                .findBySubscriptionAndPeriod(a.subscriptionId(), a.period())) {
            if (!prior.id().equals(a.id())) {
                previouslyBilled = previouslyBilled.plus(prior.amount());
            }
        }
        return new AdjustmentResult(a.id(), a.subscriptionId(),
                a.period().start().toString(), a.period().end().toString(),
                a.invoiceId(), a.amount().plus(previouslyBilled), previouslyBilled,
                a.amount(), a.status(), created, a.basisEventIds(), reason);
    }
}
