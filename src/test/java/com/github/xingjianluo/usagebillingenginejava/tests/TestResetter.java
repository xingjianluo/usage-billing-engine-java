package com.github.xingjianluo.usagebillingenginejava.tests;

import com.github.xingjianluo.usagebillingenginejava.repo.InMemoryAdjustmentRepository;
import com.github.xingjianluo.usagebillingenginejava.repo.InMemoryInvoiceRepository;
import com.github.xingjianluo.usagebillingenginejava.repo.InMemoryLedgerRepository;
import com.github.xingjianluo.usagebillingenginejava.repo.InMemoryPlanRepository;
import com.github.xingjianluo.usagebillingenginejava.repo.InMemorySubscriptionRepository;
import com.github.xingjianluo.usagebillingenginejava.repo.InMemoryUsageEventRepository;
import com.github.xingjianluo.usagebillingenginejava.service.SimulatedPaymentGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 测试夹具：每个用例前把内存仓储与模拟通道恢复到干净状态，
 * 并重新执行套餐种子（清库后由 {@code @PostConstruct} 不会再跑，故这里手动播种）。
 */
@Component
public class TestResetter {

    private static InMemoryPlanRepository planRepository;
    private static InMemorySubscriptionRepository subscriptionRepository;
    private static InMemoryUsageEventRepository usageEventRepository;
    private static InMemoryInvoiceRepository invoiceRepository;
    private static InMemoryAdjustmentRepository adjustmentRepository;
    private static InMemoryLedgerRepository ledgerRepository;
    private static SimulatedPaymentGateway gateway;
    private static TestSeeder seeder;

    @Autowired
    public TestResetter(InMemoryPlanRepository planRepository,
                        InMemorySubscriptionRepository subscriptionRepository,
                        InMemoryUsageEventRepository usageEventRepository,
                        InMemoryInvoiceRepository invoiceRepository,
                        InMemoryAdjustmentRepository adjustmentRepository,
                        InMemoryLedgerRepository ledgerRepository,
                        SimulatedPaymentGateway gateway, TestSeeder seeder) {
        TestResetter.planRepository = planRepository;
        TestResetter.subscriptionRepository = subscriptionRepository;
        TestResetter.usageEventRepository = usageEventRepository;
        TestResetter.invoiceRepository = invoiceRepository;
        TestResetter.adjustmentRepository = adjustmentRepository;
        TestResetter.ledgerRepository = ledgerRepository;
        TestResetter.gateway = gateway;
        TestResetter.seeder = seeder;
    }

    public static void resetAll() {
        invoiceRepository.clearAll();
        adjustmentRepository.clearAll();
        ledgerRepository.clearAll();
        usageEventRepository.clearAll();
        subscriptionRepository.clearAll();
        planRepository.clearAll();
        gateway.clearScripts();
        seeder.seed();
    }
}
