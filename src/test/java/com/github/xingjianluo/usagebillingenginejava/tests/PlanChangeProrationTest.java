package com.github.xingjianluo.usagebillingenginejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.xingjianluo.usagebillingenginejava.domain.Invoice;
import com.github.xingjianluo.usagebillingenginejava.domain.InvoiceStatus;
import com.github.xingjianluo.usagebillingenginejava.domain.Money;
import com.github.xingjianluo.usagebillingenginejava.domain.PlanChangeType;
import com.github.xingjianluo.usagebillingenginejava.service.InvoiceService;
import com.github.xingjianluo.usagebillingenginejava.service.LedgerService;
import com.github.xingjianluo.usagebillingenginejava.service.PaymentService;
import com.github.xingjianluo.usagebillingenginejava.service.PeriodService;
import com.github.xingjianluo.usagebillingenginejava.service.SubscriptionService;
import com.github.xingjianluo.usagebillingenginejava.service.dto.PaymentResult;
import com.github.xingjianluo.usagebillingenginejava.service.dto.PlanChangeResult;
import com.github.xingjianluo.usagebillingenginejava.tests.config.FixedClockConfig;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 周期内升降级：跨变更点费用按比例分摊，账单/信用/退款守恒，不重复扣费不漏计。
 */
@SpringBootTest
@Import(FixedClockConfig.class)
class PlanChangeProrationTest {

    private static final Logger log = LoggerFactory.getLogger(PlanChangeProrationTest.class);

    @Autowired private SubscriptionService subscriptionService;
    @Autowired private InvoiceService invoiceService;
    @Autowired private PaymentService paymentService;
    @Autowired private LedgerService ledgerService;
    @Autowired private PeriodService periodService;

    private String subId;

    @BeforeEach
    void setUp() {
        TestResetter.resetAll();
        subId = "sub-plan-" + System.nanoTime();
        subscriptionService.createSubscription(subId, "acct-" + subId, "BASIC",
                "2026-09-01T00:00:00Z");
    }

    @Test
    @DisplayName("周期中升级 BASIC->PRO（9/16 生效）：基础费按天比例分摊，净额=200.00")
    void upgradeMidCycleIsProrated() {
        PlanChangeResult change = subscriptionService.changePlan(subId, "PRO",
                "2026-09-16T00:00:00Z");
        assertEquals(PlanChangeType.UPGRADE, change.changeType());

        var period = periodService.periodOf(LocalDate.of(2026, 9, 15));
        Invoice invoice = invoiceService.getInvoice(
                invoiceService.issue(subId, period).invoiceId());

        Money baseFull = lineSum(invoice, "BASE_FULL");
        Money credit = lineSum(invoice, "BASE_CREDIT");
        Money prorated = lineSum(invoice, "BASE_PRORATED");

        log.info("[关键输入] BASIC(100.00) 前半月 + PRO(300.00) 后半月，9月共30天");
        log.info("[判定依据] BASE_FULL={}, BASE_CREDIT={}, BASE_PRORATED={}, total={}",
                baseFull, credit, prorated, invoice.total());

        // 整月 BASIC 100；变更片(后15天)信用冲回 50、新片 PRO 计 150。
        // 合计 100 - 50 + 150 = 200.00；精确分摊同值，无舍入差。
        assertEquals(Money.of("100.00"), baseFull, "起始套餐整月");
        assertEquals(Money.of("50.00"), credit, "旧套餐新片费用冲回");
        assertEquals(Money.of("150.00"), prorated, "新套餐新片费用");
        assertEquals(Money.of("200.00"), invoice.total());

        // 重放开票：同一张票、同样金额
        var again = invoiceService.issue(subId, period);
        assertEquals(invoice.id(), invoiceService.getInvoice(again.invoiceId()).id());
        assertEquals(Money.of("200.00"), invoiceService.getInvoice(again.invoiceId()).total());
    }

    @Test
    @DisplayName("周期中降级 PRO->BASIC：净额按比例下调=200.00，抵扣大于新增收费")
    void downgradeMidCycleIsProrated() {
        // 该用例单独建一个 PRO 起步订阅
        String proSub = "sub-downgrade-" + System.nanoTime();
        subscriptionService.createSubscription(proSub, "acct2", "PRO",
                "2026-09-01T00:00:00Z");
        subscriptionService.changePlan(proSub, "BASIC", "2026-09-16T00:00:00Z");

        var period = periodService.periodOf(LocalDate.of(2026, 9, 15));
        Invoice invoice = invoiceService.getInvoice(
                invoiceService.issue(proSub, period).invoiceId());

        log.info("[关键输入] PRO(300.00) 前半月 + BASIC(100.00) 后半月");
        log.info("[判定依据] BASE_FULL={}, CREDIT={}, PRORATED={}, total={}",
                lineSum(invoice, "BASE_FULL"), lineSum(invoice, "BASE_CREDIT"),
                lineSum(invoice, "BASE_PRORATED"), invoice.total());

        // 精确：300*15/30 + 100*15/30 = 200.00
        assertEquals(Money.of("200.00"), invoice.total());
    }

    @Test
    @DisplayName("支付成功后账本守恒 CHARGE = CREDIT + 实收；退款后 CHARGE = CREDIT + REFUND")
    void ledgerConservedAfterPaymentAndRefund() {
        subscriptionService.changePlan(subId, "PRO", "2026-09-16T00:00:00Z");
        var period = periodService.periodOf(LocalDate.of(2026, 9, 15));
        Invoice invoice = invoiceService.getInvoice(
                invoiceService.issue(subId, period).invoiceId());
        assertEquals(InvoiceStatus.OPEN, invoice.status());

        PaymentResult payment = paymentService.pay(invoice.id());
        assertTrue(payment.success());
        Money charge = ledgerService.sum(subId,
                com.github.xingjianluo.usagebillingenginejava.domain.LedgerEntryType.CHARGE);
        Money credit = ledgerService.sum(subId,
                com.github.xingjianluo.usagebillingenginejava.domain.LedgerEntryType.CREDIT);
        log.info("[判定依据] 支付后 CHARGE={}, CREDIT={}, 净额={}, 守恒={}",
                charge, credit, charge.minus(credit), ledgerService.isConserved(subId));
        // 账本逐行登记：CHARGE = 100(整月) + 150(新片) = 250；CREDIT = 50；
        // 净额 CHARGE-CREDIT = 200 = 发票合计。
        assertEquals(Money.of("250.00"), charge);
        assertEquals(Money.of("50.00"), credit);
        assertEquals(Money.of("200.00"), charge.minus(credit));

        PaymentResult refund = paymentService.refund(invoice.id());
        assertTrue(refund.success());
        Money refundSum = ledgerService.sum(subId,
                com.github.xingjianluo.usagebillingenginejava.domain.LedgerEntryType.REFUND);
        log.info("[判定依据] 退款后 REFUND={}, CHARGE={}, CREDIT={}, 守恒={}",
                refundSum, charge, credit, ledgerService.isConserved(subId));
        assertTrue(ledgerService.isConserved(subId),
                "退款后必须满足 CHARGE = CREDIT + REFUND");
        assertEquals(Money.of("200.00"), refundSum);

        // 终态/重复退款幂等：不产生第二笔退款
        paymentService.refund(invoice.id());
        assertEquals(Money.of("200.00"), ledgerService.sum(subId,
                com.github.xingjianluo.usagebillingenginejava.domain.LedgerEntryType.REFUND));
    }

    @Test
    @DisplayName("多次变更（BASIC->PRO->BASIC）按三段时间片分摊，金额仍可复现")
    void multipleChangesProrateBySegments() {
        subscriptionService.changePlan(subId, "PRO", "2026-09-11T00:00:00Z");
        subscriptionService.changePlan(subId, "BASIC", "2026-09-21T00:00:00Z");
        var period = periodService.periodOf(LocalDate.of(2026, 9, 15));
        Invoice i1 = invoiceService.getInvoice(invoiceService.issue(subId, period).invoiceId());

        // 三段各 10 天：100*10/30 + 300*10/30 + 100*10/30 = 166.67
        // 行模型：100 - 66.67 + 100.00 - 100.00 + 33.33 = 66.66，
        // 再由 ROUNDING_ADJUSTMENT(+0.01) 对齐纯比例分摊，合计 166.67。
        log.info("[关键输入] BASIC[1..11) PRO[11..21) BASIC[21..30]");
        log.info("[判定依据] total={}, 行数={}", i1.total(), i1.lines().size());
        assertEquals(Money.of("166.67"), i1.total());
        var again = invoiceService.issue(subId, period);
        assertEquals(i1.total(), invoiceService.getInvoice(again.invoiceId()).total());
    }

    private Money lineSum(Invoice invoice, String type) {
        return invoice.lines().stream()
                .filter(l -> l.type().equals(type))
                .map(com.github.xingjianluo.usagebillingenginejava.domain.InvoiceLineItem::amount)
                .reduce(Money.zero(), Money::plus);
    }
}
