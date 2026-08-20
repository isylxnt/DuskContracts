package dev.isylxnt.duskcontracts.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.isylxnt.duskcontracts.config.StorageConfig;
import dev.isylxnt.duskcontracts.domain.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class SqliteStorageTest {
    @TempDir Path temporary;private JdbcStorage storage;
    @BeforeEach void open(){HikariConfig cfg=new HikariConfig();cfg.setJdbcUrl("jdbc:sqlite:"+temporary.resolve("test.db"));cfg.setMaximumPoolSize(1);HikariDataSource ds=new HikariDataSource(cfg);storage=new JdbcStorage(ds,StorageConfig.Type.SQLITE,1);storage.initialize().join();}
    @AfterEach void close(){storage.close();}
    @Test void migrationCreatesVersionedSchema(){StorageStats stats=storage.stats().join();assertThat(stats.schemaVersion()).isEqualTo(3);}
    @Test void idempotentOperationPreparationReturnsOriginal(){UUID op=UUID.randomUUID();UUID actor=UUID.randomUUID();OperationRecord one=storage.prepareOperation(op,"same-key",OperationType.CREATE,actor,null,"corr","evidence").join();OperationRecord two=storage.prepareOperation(UUID.randomUUID(),"same-key",OperationType.CREATE,actor,null,"other","other").join();assertThat(two.id()).isEqualTo(one.id());}
    @Test void knownPreFundingFailureClosesPreparedOperation(){UUID op=UUID.randomUUID();storage.prepareOperation(op,"rejected-funding",OperationType.CREATE,UUID.randomUUID(),null,"corr","before funding").join();storage.failPreparedOperation(op,"provider rejected withdrawal").join();OperationRecord result=storage.operations(op.toString(),10).join().get(0);assertThat(result.state()).isEqualTo(OperationState.FAILED);assertThatThrownBy(()->storage.resolveOperation(op,UUID.randomUUID(),"REFUND","must not refund").join()).hasCauseInstanceOf(DomainException.class);}
    @Test void optimisticUpdateAllowsOnlyOneConcurrentDelivery(){
        UUID creator=UUID.randomUUID();UUID contractId=UUID.randomUUID();UUID createOp=UUID.randomUUID();Instant now=Instant.now();
        Contract contract=new Contract(contractId,"CONCUR123",creator,"creator",now,now.plusSeconds(3600),ContractStatus.OPEN,"STONE",MatchMode.MATERIAL,10,0,RewardType.MONEY,100,new byte[]{1},null,null,FulfillmentMode.PROPORTIONAL,0);
        storage.prepareOperation(createOp,"create-once",OperationType.CREATE,creator,contractId,"create","test").join();storage.commitContract(contract,createOp).join();
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),workerA=UUID.randomUUID(),workerB=UUID.randomUUID();
        storage.prepareOperation(a,"deliver-a",OperationType.CONTRIBUTE,workerA,contractId,"a","test").join();storage.prepareOperation(b,"deliver-b",OperationType.CONTRIBUTE,workerB,contractId,"b","test").join();
        CompletableFuture<ContributionResult> first=storage.commitContribution(a,workerA,"a",6,new byte[]{1},0,Instant.now(),false);
        CompletableFuture<ContributionResult> second=storage.commitContribution(b,workerB,"b",6,new byte[]{2},0,Instant.now(),false);
        long successes=java.util.stream.Stream.of(first,second).filter(f->{try{f.join();return true;}catch(CompletionException ex){return false;}}).count();
        assertThat(successes).isEqualTo(1);assertThat(storage.contract(contractId).join().orElseThrow().deliveredAmount()).isEqualTo(6);
    }
    @Test void claimingIsSingleWinnerAndReplaySafe(){
        UUID creator=UUID.randomUUID();UUID id=UUID.randomUUID();UUID op=UUID.randomUUID();Instant now=Instant.now();Contract c=new Contract(id,"CLAIM123",creator,"c",now,now.plusSeconds(60),ContractStatus.OPEN,"STONE",MatchMode.MATERIAL,1,0,RewardType.ITEM,0,new byte[]{1},new byte[]{2},null,FulfillmentMode.COMPLETE,0);
        storage.prepareOperation(op,"create-claim",OperationType.CREATE,creator,id,"c","test").join();storage.commitContract(c,op).join();UUID cancel=UUID.randomUUID();storage.prepareOperation(cancel,"cancel-claim",OperationType.CANCEL,creator,id,"x","test").join();storage.cancel(id,creator,"test",false,cancel,Instant.now()).join();ClaimRecord claim=storage.claims(creator,10).join().get(0);
        assertThat(storage.reserveClaim(claim.id(),creator).join()).isPresent();assertThat(storage.reserveClaim(claim.id(),creator).join()).isEmpty();storage.completeClaim(claim.id()).join();assertThat(storage.reserveClaim(claim.id(),creator).join()).isEmpty();
    }
    @Test void directedContractsAreVisibleToTargetAndCreatorOnly(){
        UUID creator=UUID.randomUUID(),target=UUID.randomUUID(),outsider=UUID.randomUUID(),id=UUID.randomUUID(),op=UUID.randomUUID();Instant now=Instant.now();
        Contract c=new Contract(id,"PRIVATE123",creator,"creator",now,now.plusSeconds(60),ContractStatus.OPEN,"STONE",MatchMode.MATERIAL,1,0,RewardType.MONEY,100,new byte[]{1},null,target,FulfillmentMode.COMPLETE,0);
        storage.prepareOperation(op,"create-private",OperationType.CREATE,creator,id,"c","test").join();storage.commitContract(c,op).join();
        assertThat(storage.browse(filter(creator)).join()).extracting(ContractSummary::id).containsExactly(id);
        assertThat(storage.browse(filter(target)).join()).extracting(ContractSummary::id).containsExactly(id);
        assertThat(storage.browse(filter(outsider)).join()).isEmpty();
    }
    @Test void assassinationAllowsCreatorAsTargetAndRequiresStarting(){
        UUID creator=UUID.randomUUID(),killer=UUID.randomUUID(),id=UUID.randomUUID(),op=UUID.randomUUID();Instant now=Instant.now();
        Contract bounty=new Contract(id,"KILL123",creator,"creator",now,now.plusSeconds(60),ContractStatus.OPEN,"PLAYER_HEAD",MatchMode.EXACT,1,0,RewardType.MONEY,500,null,null,creator,FulfillmentMode.COMPLETE,0);
        storage.prepareOperation(op,"create-kill",OperationType.CREATE,creator,id,"k","test").join();storage.commitContract(bounty,op).join();
        assertThat(storage.browse(filter(UUID.randomUUID())).join()).extracting(ContractSummary::id).containsExactly(id);
        assertThat(storage.completeAssassinations(killer,"killer",creator,Instant.now(),false,java.time.Duration.ofMinutes(15)).join()).isEmpty();
        assertThat(storage.joinAssassination(id,killer,Instant.now(),false).join()).isTrue();
        assertThat(storage.joinAssassination(id,killer,Instant.now(),false).join()).isFalse();
        assertThat(storage.isParticipating(id,killer).join()).isTrue();
        assertThat(storage.participating(killer,10,Instant.now()).join()).extracting(ContractSummary::id).containsExactly(id);
        assertThat(storage.completeAssassinations(killer,"killer",creator,Instant.now(),false,java.time.Duration.ofMinutes(15)).join()).hasSize(1);
        assertThat(storage.contract(id).join().orElseThrow().status()).isEqualTo(ContractStatus.COMPLETED);
        assertThat(storage.participating(killer,10,Instant.now()).join()).isEmpty();
        assertThat(storage.claims(killer,10).join()).singleElement().satisfies(claim->{assertThat(claim.type()).isEqualTo(ClaimType.MONEY_REWARD);assertThat(claim.moneyMinor()).isEqualTo(500);});
    }
    @Test void safeMultiItemReturnsRemainReturnPendingAfterRelease(){
        UUID player=UUID.randomUUID();storage.storeItemReturn(player,null,new byte[]{1,2,3},"test").join();
        ClaimRecord claim=storage.claims(player,10).join().get(0);assertThat(claim.type()).isEqualTo(ClaimType.ITEM_BUNDLE_RETURN);
        assertThat(storage.reserveClaim(claim.id(),player).join()).isPresent();storage.releaseClaim(claim.id(),"full").join();
        assertThat(storage.claims(player,10).join().get(0).state()).isEqualTo(ClaimState.RETURN_PENDING);
    }
    @Test void repeatedAssassinationFarmingIsBlockedButCanBeExplicitlyBypassed(){
        UUID creator=UUID.randomUUID(),killer=UUID.randomUUID(),target=UUID.randomUUID();Instant now=Instant.now();
        Contract first=createBounty(creator,target,"FARM000000000001",now);
        assertThat(storage.joinAssassination(first.id(),killer,now,false).join()).isTrue();
        assertThat(storage.completeAssassinations(killer,"killer",target,now.plusSeconds(1),false,java.time.Duration.ofMinutes(15)).join()).hasSize(1);
        Contract second=createBounty(creator,target,"FARM000000000002",now.plusSeconds(2));
        assertThat(storage.joinAssassination(second.id(),killer,now.plusSeconds(2),false).join()).isTrue();
        assertThat(storage.completeAssassinations(killer,"killer",target,now.plusSeconds(3),false,java.time.Duration.ofMinutes(15)).join()).isEmpty();
        assertThat(storage.completeAssassinations(killer,"killer",target,now.plusSeconds(3),false,java.time.Duration.ZERO).join()).hasSize(1);
    }
    @Test void maintenancePurgesOnlyThroughValidatedBoundedBatches(){
        storage.audit(UUID.randomUUID(),null,null,"TEST","old enough at future cutoff").join();
        assertThat(storage.purgeMaintenance(Instant.now().plusSeconds(1),100).join()).isPositive();
        assertThatThrownBy(()->storage.purgeMaintenance(Instant.now(),0).join()).hasCauseInstanceOf(IllegalArgumentException.class);
    }
    private Contract createBounty(UUID creator,UUID target,String shortId,Instant now){
        UUID id=UUID.randomUUID(),operation=UUID.randomUUID();
        Contract bounty=new Contract(id,shortId,creator,"creator",now,now.plusSeconds(3600),ContractStatus.OPEN,"PLAYER_HEAD",MatchMode.EXACT,1,0,RewardType.MONEY,500,null,null,target,FulfillmentMode.COMPLETE,0);
        storage.prepareOperation(operation,"create-"+id,OperationType.CREATE,creator,id,"bounty","test").join();
        storage.commitContract(bounty,operation).join();
        return bounty;
    }
    private static ContractFilter filter(UUID viewer){return new ContractFilter(ContractStatus.OPEN,null,null,null,ContractFilter.Sort.NEWEST,viewer,0,10);}
}
