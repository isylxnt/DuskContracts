package dev.isylxnt.duskcontracts.persistence;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractFilterTest {
    @Test void computesLargeOffsetsWithoutIntegerOverflow() {
        ContractFilter filter = new ContractFilter(null, null, null, null,
                ContractFilter.Sort.NEWEST, UUID.randomUUID(), 30_000_000, 100);
        assertThat(filter.offset()).isEqualTo(3_000_000_000L);
    }

    @Test void rejectsInvalidOrContextFreePagination() {
        UUID viewer = UUID.randomUUID();
        assertThatThrownBy(() -> ContractFilter.browse(viewer, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContractFilter(null, null, null, null,
                ContractFilter.Sort.NEWEST, viewer, 0, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContractFilter(null, null, null, null,
                null, viewer, 0, 10)).isInstanceOf(NullPointerException.class);
    }
}
