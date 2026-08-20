package dev.isylxnt.duskcontracts.api;

import dev.isylxnt.duskcontracts.application.ContractViewMapper;
import dev.isylxnt.duskcontracts.persistence.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class DuskContractsApiImpl implements DuskContractsApi {
    private final Storage storage; private final String version;
    private final ContractService contracts = new ContractService() {
        @Override public CompletableFuture<Optional<ContractView>> find(String shortId) { return storage.contract(shortId).thenApply(found -> found.map(ContractViewMapper::from)); }
        @Override public CompletableFuture<List<ContractView>> browse(int page, int pageSize) {
            return storage.browse(new ContractFilter(dev.isylxnt.duskcontracts.domain.ContractStatus.OPEN, null, null, null,
                    ContractFilter.Sort.NEWEST, new UUID(0, 0), page, pageSize)).thenApply(list -> list.stream().map(ContractViewMapper::from).toList());
        }
    };
    private final ClaimService claims;
    public DuskContractsApiImpl(Storage storage, String version) { this.storage = storage; this.version = version; this.claims = player -> storage.pendingClaimCount(player); }
    @Override public ContractService contracts() { return contracts; }
    @Override public ClaimService claims() { return claims; }
    @Override public String version() { return version; }
}
