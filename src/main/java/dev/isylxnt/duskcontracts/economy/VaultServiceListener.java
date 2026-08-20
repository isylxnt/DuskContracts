package dev.isylxnt.duskcontracts.economy;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;

public final class VaultServiceListener implements Listener {
    private final EconomyBridge economy;
    public VaultServiceListener(EconomyBridge economy) { this.economy = economy; }
    @EventHandler public void onRegister(ServiceRegisterEvent event) { if (event.getProvider().getService().getName().equals("net.milkbowl.vault.economy.Economy")) economy.refresh(); }
    @EventHandler public void onUnregister(ServiceUnregisterEvent event) { if (event.getProvider().getService().getName().equals("net.milkbowl.vault.economy.Economy")) economy.refresh(); }
}
