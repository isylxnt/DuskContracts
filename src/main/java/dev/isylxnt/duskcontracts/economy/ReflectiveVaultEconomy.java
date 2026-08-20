package dev.isylxnt.duskcontracts.economy;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

public final class ReflectiveVaultEconomy implements EconomyBridge {
    private final Logger logger;
    private final AtomicReference<Object> provider = new AtomicReference<>();
    private volatile Class<?> economyType;
    public ReflectiveVaultEconomy(Logger logger) { this.logger = logger; refresh(); }

    @Override public void refresh() {
        try {
            economyType = Class.forName("net.milkbowl.vault.economy.Economy");
            @SuppressWarnings({"rawtypes", "unchecked"})
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration((Class) economyType);
            provider.set(registration == null ? null : registration.getProvider());
            if (registration == null) logger.info("Vault was found, but no economy provider is registered; money contracts are disabled.");
            else logger.info("Vault economy provider: " + providerName());
        } catch (ClassNotFoundException ex) {
            economyType = null; provider.set(null);
            logger.info("Vault is not installed; item-reward contracts remain available and money contracts are disabled.");
        } catch (RuntimeException ex) {
            provider.set(null); logger.warning("Vault economy discovery failed: " + ex.getMessage());
        }
    }
    @Override public boolean available() { return provider.get() != null; }
    @Override public String providerName() {
        Object value = provider.get(); if (value == null) return "unavailable";
        try { return String.valueOf(invoke(value, "getName")); } catch (RuntimeException ex) { return value.getClass().getSimpleName(); }
    }
    @Override public Result withdraw(OfflinePlayer player, long minorUnits, int places) { return transact("withdrawPlayer", player, minorUnits, places); }
    @Override public Result deposit(OfflinePlayer player, long minorUnits, int places) { return transact("depositPlayer", player, minorUnits, places); }
    private Result transact(String method, OfflinePlayer player, long minor, int places) {
        Object value = provider.get(); if (value == null) return new Result(false, "No economy provider is available");
        double amount = BigDecimal.valueOf(minor, places).doubleValue();
        try {
            Object response = invokeCompatible(value, method, player, amount);
            boolean success = (boolean) invoke(response, "transactionSuccess");
            String error = String.valueOf(invoke(response, "errorMessage"));
            return new Result(success, success ? "" : (error == null || error.isBlank() ? "The economy provider rejected the transaction" : error));
        } catch (RuntimeException ex) { return new Result(false, "Economy provider error: " + ex.getMessage()); }
    }
    private static Object invoke(Object target, String name) { return invokeCompatible(target, name); }
    private static Object invokeCompatible(Object target, String name, Object... args) {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            try { return method.invoke(target, args); }
            catch (IllegalArgumentException ignored) { }
            catch (IllegalAccessException | InvocationTargetException ex) { throw new IllegalStateException(ex.getCause() == null ? ex : ex.getCause()); }
        }
        throw new IllegalStateException("Vault method is missing: " + name);
    }
}
