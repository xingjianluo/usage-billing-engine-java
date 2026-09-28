package com.github.xingjianluo.usagebillingenginejava.web;

import com.github.xingjianluo.usagebillingenginejava.domain.BillingPeriod;
import com.github.xingjianluo.usagebillingenginejava.service.AdjustmentService;
import com.github.xingjianluo.usagebillingenginejava.service.PeriodService;
import com.github.xingjianluo.usagebillingenginejava.service.dto.AdjustmentPaymentResult;
import com.github.xingjianluo.usagebillingenginejava.service.dto.AdjustmentView;
import com.github.xingjianluo.usagebillingenginejava.web.dto.AdjustmentRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 结算后补报与补差入口。
 * 用量上报仍走既有 /api/usage-events（兼容不变，迟到事件正常接收、不丢弃）；
 * 补差结算单独触发，产出独立补差单，不改写原发票。
 */
@RestController
@RequestMapping("/api")
public class AdjustmentController {

    private final AdjustmentService adjustmentService;
    private final PeriodService periodService;
    private final Clock clock;

    public AdjustmentController(AdjustmentService adjustmentService,
                                PeriodService periodService, Clock clock) {
        this.adjustmentService = adjustmentService;
        this.periodService = periodService;
        this.clock = clock;
    }

    @PostMapping("/subscriptions/{id}/adjustments")
    public Object settle(@PathVariable String id,
                         @RequestBody(required = false) AdjustmentRequest request) {
        LocalDate anchor = request == null || request.period() == null
                ? null : LocalDate.parse(request.period());
        BillingPeriod period = anchor == null
                ? periodService.periodContaining(Instant.now(clock))
                : periodService.periodOf(anchor);
        String batchId = request == null ? null : request.batchId();
        return adjustmentService.settle(id, period, batchId);
    }

    @GetMapping("/adjustments/{adjustmentId}")
    public AdjustmentView get(@PathVariable String adjustmentId) {
        return AdjustmentView.from(adjustmentService.getAdjustment(adjustmentId));
    }

    @PostMapping("/adjustments/{adjustmentId}/pay")
    public AdjustmentPaymentResult pay(@PathVariable String adjustmentId) {
        return adjustmentService.pay(adjustmentId);
    }

    @PostMapping("/adjustments/{adjustmentId}/retry")
    public AdjustmentPaymentResult retry(@PathVariable String adjustmentId) {
        return adjustmentService.retry(adjustmentId);
    }

    @PostMapping("/adjustments/{adjustmentId}/refund")
    public AdjustmentPaymentResult refund(@PathVariable String adjustmentId) {
        return adjustmentService.refund(adjustmentId);
    }
}
