package dev.isylxnt.duskcontracts.application;

import dev.isylxnt.duskcontracts.api.ContractView;
import dev.isylxnt.duskcontracts.domain.Contract;
import dev.isylxnt.duskcontracts.domain.ContractStatus;
import dev.isylxnt.duskcontracts.persistence.ContractSummary;

public final class ContractViewMapper {
    private ContractViewMapper() { }

    public static ContractView from(Contract contract) {
        return state(contract, contract.status(), contract.deliveredAmount(), contract.version());
    }

    public static ContractView from(ContractSummary contract) {
        return new ContractView(contract.id(), contract.shortId(), contract.creatorId(), contract.creatorName(),
                contract.createdAt(), contract.expiresAt(), contract.status(), contract.material(), contract.matchMode(),
                contract.totalAmount(), contract.deliveredAmount(), contract.rewardType(), contract.rewardMinor(),
                contract.targetId(), contract.fulfillmentMode(), contract.version());
    }

    public static ContractView state(Contract contract, ContractStatus status, long deliveredAmount, long version) {
        return new ContractView(contract.id(), contract.shortId(), contract.creatorId(), contract.creatorName(),
                contract.createdAt(), contract.expiresAt(), status, contract.material(), contract.matchMode(),
                contract.totalAmount(), deliveredAmount, contract.rewardType(), contract.rewardMinor(),
                contract.targetId(), contract.fulfillmentMode(), version);
    }
}
