package pl.galaxy.tpa;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

public class VaultHook {
    private final Economy econ;

    public VaultHook() {
        RegisteredServiceProvider<Economy> rsp =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        econ = rsp != null ? rsp.getProvider() : null;
    }

    public double balance(Player p) {
        return econ != null ? econ.getBalance(p) : 0;
    }
}
