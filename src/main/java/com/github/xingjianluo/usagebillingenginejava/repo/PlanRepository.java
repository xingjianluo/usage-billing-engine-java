package com.github.xingjianluo.usagebillingenginejava.repo;

import com.github.xingjianluo.usagebillingenginejava.domain.Plan;
import java.util.Optional;

public interface PlanRepository {
    void save(Plan plan);

    Optional<Plan> findByCode(String code);
}
