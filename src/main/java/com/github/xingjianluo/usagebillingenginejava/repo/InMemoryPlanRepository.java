package com.github.xingjianluo.usagebillingenginejava.repo;

import com.github.xingjianluo.usagebillingenginejava.domain.Plan;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * 套餐内存仓储：按 code 唯一。
 */
@Repository
public class InMemoryPlanRepository implements PlanRepository {

    private final ConcurrentHashMap<String, Plan> plans = new ConcurrentHashMap<>();

    @Override
    public void save(Plan plan) {
        plans.put(plan.code(), plan);
    }

    @Override
    public Optional<Plan> findByCode(String code) {
        return Optional.ofNullable(plans.get(code));
    }

    /** 仅供测试隔离使用：清空全部数据。 */
    public void clearAll() {
        plans.clear();
    }
}
