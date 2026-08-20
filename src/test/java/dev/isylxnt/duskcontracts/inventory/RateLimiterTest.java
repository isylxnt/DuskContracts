package dev.isylxnt.duskcontracts.inventory;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimiterTest {
    @Test void enforcesPerPlayerLimitAndCanForgetSessions() {
        RateLimiter limiter = new RateLimiter(2);
        UUID player = UUID.randomUUID();
        assertThat(limiter.allow(player)).isTrue();
        assertThat(limiter.allow(player)).isTrue();
        assertThat(limiter.allow(player)).isFalse();
        limiter.remove(player);
        assertThat(limiter.allow(player)).isTrue();
        limiter.clear();
        assertThat(limiter.allow(player)).isTrue();
    }

    @Test void rejectsAnUnsafeConfiguration() {
        assertThatThrownBy(() -> new RateLimiter(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
