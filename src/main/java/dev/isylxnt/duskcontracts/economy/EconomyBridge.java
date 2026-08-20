package dev.isylxnt.duskcontracts.economy;

import org.bukkit.OfflinePlayer;

public interface EconomyBridge {
    boolean available();
    String providerName();
    Result withdraw(OfflinePlayer player, long minorUnits, int decimalPlaces);
    Result deposit(OfflinePlayer player, long minorUnits, int decimalPlaces);
    void refresh();
    record Result(boolean success, String reason) { }
}
