package dev.isylxnt.duskcontracts.domain;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class MoneyAndParsersTest {
    @Test void parsesExactDecimalMoney(){assertThat(Money.parse("5000.00",2).minorUnits()).isEqualTo(500_000);}
    @Test void rejectsExponentNaNNegativeAndExcessScale(){
        assertThatThrownBy(()->Money.parse("1e9",2)).isInstanceOf(DomainException.class);
        assertThatThrownBy(()->Money.parse("NaN",2)).isInstanceOf(DomainException.class);
        assertThatThrownBy(()->Money.parse("-1.00",2)).isInstanceOf(DomainException.class);
        assertThatThrownBy(()->Money.parse("1.001",2)).isInstanceOf(DomainException.class);
    }
    @Test void parsesBoundedAmountsAndDurations(){assertThat(Parsers.positiveAmount("1024",2000)).isEqualTo(1024);assertThat(Parsers.duration("7d")).isEqualTo(Duration.ofDays(7));}
    @Test void rejectsZeroAndMalformedValues(){assertThatThrownBy(()->Parsers.positiveAmount("0",10)).isInstanceOf(DomainException.class);assertThatThrownBy(()->Parsers.duration("1 day")).isInstanceOf(DomainException.class);}
}
