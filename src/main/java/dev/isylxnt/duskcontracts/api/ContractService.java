package dev.isylxnt.duskcontracts.api;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface ContractService {
    CompletableFuture<Optional<ContractView>> find(String shortId);
    CompletableFuture<List<ContractView>> browse(int page, int pageSize);
}
