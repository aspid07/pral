package com.lowcode.platform.auth;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ревью CTO, п.1.6: JwtAuthenticationFilter должен проверять, что пользователь
 * ещё существует и включён — не только что подпись валидна. Но ходить в БД на
 * КАЖДЫЙ запрос ради этого — дорого. Простой TTL-кеш в памяти вместо
 * Spring Cache abstraction: дефолтный ConcurrentMapCacheManager не поддерживает
 * TTL вообще (записи живут вечно, пока не evict вручную), а тащить Caffeine
 * ради одного кеша на 30 секунд — лишняя зависимость для этой задачи.
 */
@Component
public class UserStatusCache {

    private static final Duration TTL = Duration.ofSeconds(30);

    private final AppUserRepository userRepository;
    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();

    public UserStatusCache(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public boolean isEnabled(UUID userId) {
        Entry entry = cache.get(userId);
        if (entry != null && Duration.between(entry.checkedAt(), Instant.now()).compareTo(TTL) < 0) {
            return entry.enabled();
        }
        boolean enabled = userRepository.findById(userId).map(AppUser::isEnabled).orElse(false);
        cache.put(userId, new Entry(enabled, Instant.now()));
        return enabled;
    }

    private record Entry(boolean enabled, Instant checkedAt) {}
}
