package dev.isylxnt.duskcontracts.inventory;

import dev.isylxnt.duskcontracts.domain.DomainException;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

@SuppressWarnings("deprecation")
public final class ItemSerializer {
    private final int maximumBytes;
    public ItemSerializer(int maximumBytes) { this.maximumBytes = maximumBytes; }
    public byte[] serialize(ItemStack source) {
        if (source == null || source.getType().isAir()) throw new DomainException(DomainException.Kind.VALIDATION, "Item cannot be empty");
        ItemStack copy = source.clone();
        byte[] payload = copy.serializeAsBytes();
        return PayloadEnvelope.encode(1, Bukkit.getUnsafe().getDataVersion(), "BUKKIT_BYTES_V1", payload, maximumBytes);
    }
    public byte[] serializeForRecovery(ItemStack source) {
        if (source == null || source.getType().isAir()) throw new DomainException(DomainException.Kind.VALIDATION, "Item cannot be empty");
        byte[] payload = source.clone().serializeAsBytes();
        return PayloadEnvelope.encode(1, Bukkit.getUnsafe().getDataVersion(), "BUKKIT_BYTES_V1", payload, payload.length);
    }
    public ItemStack deserialize(byte[] envelope) {
        PayloadEnvelope.Decoded decoded = PayloadEnvelope.decode(envelope, maximumBytes);
        if (decoded.schemaVersion() != 1 || !decoded.algorithm().equals("BUKKIT_BYTES_V1"))
            throw new DomainException(DomainException.Kind.PERMANENT, "Unsupported item serialization algorithm");
        try { return ItemStack.deserializeBytes(decoded.payload()); }
        catch (RuntimeException ex) { throw new DomainException(DomainException.Kind.PERMANENT, "Server could not deserialize stored item", ex); }
    }
}
