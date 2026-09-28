package com.github.xingjianluo.usagebillingenginejava.tests;

import com.github.xingjianluo.usagebillingenginejava.repo.InMemoryAdjustmentRepository;
import com.github.xingjianluo.usagebillingenginejava.repo.InMemoryInvoiceRepository;
import com.github.xingjianluo.usagebillingenginejava.repo.InMemoryLedgerRepository;
import com.github.xingjianluo.usagebillingenginejava.repo.InMemoryPlanRepository;
import com.github.xingjianluo.usagebillingenginejava.repo.InMemorySubscriptionRepository;
import com.github.xingjianluo.usagebillingenginejava.repo.InMemoryUsageEventRepository;
import com.github.xingjianluo.usagebillingenginejava.service.SimulatedPaymentGateway;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 测试夹具：每个用例前把内存仓储与模拟通道恢复到干净状态，
 * 并重新执行套餐种子（清库后由 {@code @PostConstruct} 不会再跑，故这里手动播种）。
 *
 * 注意：{@code @SpringBootTest} 与 {@code @AutoConfigureMockMvc} 会构建多个互相独立的
 * Spring 上下文（各自持有一套内存仓储）。若只保留“最后一个上下文”的静态引用，
 * 其它上下文里的测试执行 resetAll 时会清错库。因此这里登记所有上下文的实例，
 * resetAll 时逐套清空，保证任意上下文的用例都从干净状态开始。
 */
@Component
public class TestResetter {

    private record Holder(InMemoryPlanRepository planRepository,
                          InMemorySubscriptionRepository subscriptionRepository,
                          InMemoryUsageEventRepository usageEventRepository,
                          InMemoryInvoiceRepository invoiceRepository,
                          InMemoryLedgerRepository ledgerRepository,
                          InMemoryAdjustmentRepository adjustmentRepository,
                          SimulatedPaymentGateway gateway, TestSeeder seeder) {
    }

    private static final List<Holder> HOLDERS = new CopyOnWriteArrayList<>();

    @Autowired
    public TestResetter(InMemoryPlanRepository planRepository,
                        InMemorySubscriptionRepository subscriptionRepository,
                        InMemoryUsageEventRepository usageEventRepository,
                        InMemoryInvoiceRepository invoiceRepository,
                        InMemoryLedgerRepository ledgerRepository,
                        InMemoryAdjustmentRepository adjustmentRepository,
                        SimulatedPaymentGateway gateway, TestSeeder seeder) {
        HOLDERS.add(new Holder(planRepository, subscriptionRepository, usageEventRepository,
                invoiceRepository, ledgerRepository, adjustmentRepository, gateway, seeder));
    }

    public static void resetAll() {
        for (Holder h : HOLDERS) {
            h.adjustmentRepository().clearAll();
            h.invoiceRepository().clearAll();
            h.ledgerRepository().clearAll();
            h.usageEventRepository().clearAll();
            h.subscriptionRepository().clearAll();
            h.planRepository().clearAll();
            h.gateway().clearScripts();
            h.seeder().seed();
        }
    }
}
