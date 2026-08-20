package dev.isylxnt.duskcontracts.api;

public interface DuskContractsApi {
    ContractService contracts();
    ClaimService claims();
    String version();
}
