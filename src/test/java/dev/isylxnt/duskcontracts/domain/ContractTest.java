package dev.isylxnt.duskcontracts.domain;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ContractTest {
    private final UUID creator=UUID.randomUUID();private final UUID worker=UUID.randomUUID();
    @Test void enforcesPrivateAndOwnFulfillment(){Contract c=contract(worker,FulfillmentMode.PROPORTIONAL,RewardType.MONEY);assertThatThrownBy(()->c.validateContribution(UUID.randomUUID(),1,Instant.now(),false)).isInstanceOf(DomainException.class);assertThatThrownBy(()->c.validateContribution(creator,1,Instant.now(),false)).isInstanceOf(DomainException.class);c.validateContribution(worker,1,Instant.now(),false);}
    @Test void completeRequiresAllRemaining(){Contract c=contract(null,FulfillmentMode.COMPLETE,RewardType.MONEY);assertThatThrownBy(()->c.validateContribution(worker,9,Instant.now(),false)).isInstanceOf(DomainException.class);c.validateContribution(worker,10,Instant.now(),false);}
    @Test void itemRewardsCannotBeProportional(){assertThatThrownBy(()->contract(null,FulfillmentMode.PROPORTIONAL,RewardType.ITEM)).isInstanceOf(DomainException.class);}
    private Contract contract(UUID target,FulfillmentMode mode,RewardType type){Instant now=Instant.now();return new Contract(UUID.randomUUID(),"ABC123",creator,"creator",now,now.plusSeconds(3600),ContractStatus.OPEN,"STONE",MatchMode.SIMILAR,10,0,type,type==RewardType.MONEY?100:0,new byte[]{1},type==RewardType.ITEM?new byte[]{2}:null,target,mode,0);}
}
