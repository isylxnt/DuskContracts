package dev.isylxnt.duskcontracts.api.event;
import dev.isylxnt.duskcontracts.api.ContractView; import org.bukkit.event.Event; import org.bukkit.event.HandlerList;
public final class ContractExpiredEvent extends Event { private static final HandlerList HANDLERS=new HandlerList(); private final ContractView contract; public ContractExpiredEvent(ContractView contract,boolean async){super(async);this.contract=contract;} public ContractView contract(){return contract;} @Override public HandlerList getHandlers(){return HANDLERS;} public static HandlerList getHandlerList(){return HANDLERS;} }
