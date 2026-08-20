package dev.isylxnt.duskcontracts.inventory;

import dev.isylxnt.duskcontracts.domain.MatchMode;
import org.bukkit.inventory.ItemStack;
import java.util.Arrays;

public final class ItemMatcher {
    public boolean matches(ItemStack expected, ItemStack offered, MatchMode mode) {
        if (expected == null || offered == null || offered.getType().isAir()) return false;
        return switch (mode) {
            case MATERIAL -> expected.getType() == offered.getType();
            case SIMILAR -> expected.isSimilar(offered);
            case EXACT -> Arrays.equals(normalized(expected), normalized(offered));
        };
    }
    private byte[] normalized(ItemStack item) { ItemStack copy = item.clone(); copy.setAmount(1); return copy.serializeAsBytes(); }
}
