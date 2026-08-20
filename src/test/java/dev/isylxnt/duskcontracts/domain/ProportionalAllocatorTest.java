package dev.isylxnt.duskcontracts.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProportionalAllocatorTest {
    @Test void preservesEveryMinorUnitAcrossUnevenContributions(){
        long funded=10_001;long delivered=0;long paid=0;long[] parts={1,2,3,1};
        for(long part:parts){long allocation=ProportionalAllocator.allocate(funded,7,delivered,part);paid+=allocation;delivered+=part;}
        assertThat(paid).isEqualTo(funded);
    }
    @Test void intermediatePaymentsRoundDown(){
        assertThat(ProportionalAllocator.allocate(100,3,0,1)).isEqualTo(33);
        assertThat(ProportionalAllocator.allocate(100,3,1,1)).isEqualTo(33);
        assertThat(ProportionalAllocator.allocate(100,3,2,1)).isEqualTo(34);
    }
    @Test void rejectsOverDelivery(){assertThatThrownBy(()->ProportionalAllocator.allocate(100,5,4,2)).isInstanceOf(DomainException.class);}
}
