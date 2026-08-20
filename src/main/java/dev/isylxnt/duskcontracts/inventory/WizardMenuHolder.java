package dev.isylxnt.duskcontracts.inventory;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

final class WizardMenuHolder implements InventoryHolder {
    enum Type { TYPE, EDITOR, ASSASSINATION, TIME, REWARD, REWARD_ITEMS, CONFIRM, ASSASSINATION_CONFIRM }

    private final UUID sessionId;
    private final Type type;
    private Inventory inventory;

    WizardMenuHolder(UUID sessionId, Type type) {
        this.sessionId = sessionId;
        this.type = type;
    }

    UUID sessionId() { return sessionId; }
    Type type() { return type; }
    void inventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public Inventory getInventory() { return inventory; }
}
