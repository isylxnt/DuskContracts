package dev.isylxnt.duskcontracts.inventory;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import java.util.UUID;

public final class ContractMenuHolder implements InventoryHolder {
    public enum Type { MAIN, BOOK, BROWSE, DETAIL, DELIVERY, CLAIMS, CONTRIBUTIONS }
    private final UUID sessionId; private final Type type; private Inventory inventory;
    public ContractMenuHolder(Type type) { this.sessionId=UUID.randomUUID(); this.type=type; }
    public UUID sessionId(){return sessionId;} public Type type(){return type;} public void inventory(Inventory inventory){this.inventory=inventory;}
    @Override public Inventory getInventory(){return inventory;}
}
