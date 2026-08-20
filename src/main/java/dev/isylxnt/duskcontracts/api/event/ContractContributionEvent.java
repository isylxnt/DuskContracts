package dev.isylxnt.duskcontracts.api.event;

import dev.isylxnt.duskcontracts.api.ContractView;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import java.util.UUID;

public final class ContractContributionEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList(); private final ContractView contract; private final UUID contributor; private final long amount; private final long payoutMinor;
    public ContractContributionEvent(ContractView contract, UUID contributor, long amount, long payoutMinor, boolean async) { super(async); this.contract=contract; this.contributor=contributor; this.amount=amount; this.payoutMinor=payoutMinor; }
    public ContractView contract(){return contract;} public UUID contributor(){return contributor;} public long amount(){return amount;} public long payoutMinor(){return payoutMinor;}
    @Override public HandlerList getHandlers(){return HANDLERS;} public static HandlerList getHandlerList(){return HANDLERS;}
}
