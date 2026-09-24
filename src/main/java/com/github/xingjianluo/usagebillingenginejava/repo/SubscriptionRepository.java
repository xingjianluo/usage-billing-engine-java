package com.github.xingjianluo.usagebillingenginejava.repo;

import com.github.xingjianluo.usagebillingenginejava.domain.Subscription;
import java.util.Optional;

public interface SubscriptionRepository {
    void save(Subscription subscription);

    Optional<Subscription> findById(String id);
}
