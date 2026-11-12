package pl.galaxy.tpa;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class GalaxyTPA extends JavaPlugin implements Listener, CommandExecutor {

    // odbiorca -> (nadawca -> czas wysłania prośby w ms)
    private final Map<UUID, Map<UUID, Long>> requests = new HashMap<>();
    // nadawca -> aktywne odliczanie
    private final Map<UUID, BukkitTask> countdowns = new HashMap<>();
    private VaultHook vault;

    // Ukryta komenda wewnętrzna (NIE jest w plugin.yml, nie ma permisji ani tab-complete).
    private static final String OPEN = "/gtpa-open ";

    private enum GuiType { SEND, ACCEPT }

    private static final class TpaHolder implements InventoryHolder {
        final GuiType type;
        final UUID other;
        final Map<Integer, String[]> actions = new HashMap<>(); // slot -> {lewy klik, prawy klik}
        Inventory inv;

        TpaHolder(GuiType type, UUID other) {
            this.type = type;
            this.other = other;
        }

        @Override public Inventory getInventory() { return inv; }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            try { vault = new VaultHook(); } catch (Throwable ignored) {}
        }
        for (String c : List.of("tpa", "tpaccept", "tpdeny", "galaxytpa"))
            Objects.requireNonNull(getCommand(c)).setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        countdowns.values().forEach(BukkitTask::cancel);
        countdowns.clear();
    }

    // ---------------- POMOCNICZE ----------------

    private static String col(String s) { return ChatColor.translateAlternateColorCodes('&', s); }

    private static BaseComponent[] comp(String s) { return TextComponent.fromLegacyText(col(s)); }

    private static TextComponent line(String s) {
        TextComponent t = new TextComponent();
        for (BaseComponent b : comp(s)) t.addExtra(b);
        return t;
    }

    private static TextComponent clickable(String text, String command, String hover) {
        TextComponent t = line(text);
        t.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
        t.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(comp(hover))));
        return t;
    }

    // podmienia {klucz} na wartość: ph(tekst, "player", "Steve", "seconds", "5")
    private static String ph(String s, String... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        return s;
    }

    private String cfg(String path) {
        String s = getConfig().getString(path);
        return s == null ? "" : s;
    }

    // wysyła wiadomość z configu (messages.<klucz>); pusty tekst = wiadomość wyłączona
    private void say(CommandSender p, String key, String... kv) {
        String s = cfg("messages." + key);
        if (s.isEmpty()) return;
        p.sendMessage(col(ph(s, kv)));
    }

    private void actionBar(Player p, String s) {
        if (!getConfig().getBoolean("settings.actionbar-enabled", true) || s.isEmpty()) return;
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, comp(s));
    }

    private void subtitle(Player p, String s) {
        if (!getConfig().getBoolean("settings.title-enabled", true) || s.isEmpty()) return;
        p.sendTitle("", col(s), 0, getConfig().getInt("settings.title-stay-ticks", 30), 5);
    }

    // ---------------- PROŚBY ----------------

    private boolean hasRequest(UUID to, UUID from) {
        Map<UUID, Long> m = requests.get(to);
        if (m == null) return false;
        Long t = m.get(from);
        if (t == null) return false;
        int exp = getConfig().getInt("settings.request-expire-seconds", 0);
        if (exp > 0 && System.currentTimeMillis() - t > exp * 1000L) {
            m.remove(from);
            return false;
        }
        return true;
    }

    private boolean takeRequest(UUID to, UUID from) {
        if (!hasRequest(to, from)) return false;
        requests.get(to).remove(from);
        return true;
    }

    // ---------------- CHAT: hover ze statystykami + klik ----------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        if (!getConfig().getBoolean("chat.enabled", true)) return;
        final Player src = e.getPlayer();
        final String message = e.getMessage();
        final Set<Player> recipients = new HashSet<>(e.getRecipients());
        e.setCancelled(true);

        // statystyki czytamy na głównym wątku
        Bukkit.getScheduler().runTask(this, () -> {
            String fmt = ph(cfg("chat.format"), "player", src.getName());
            int idx = fmt.indexOf("{message}");
            String before = idx >= 0 ? fmt.substring(0, idx) : fmt;
            String after = idx >= 0 ? fmt.substring(idx + 9) : "";

            List<BaseComponent> parts = new ArrayList<>();
            parts.addAll(Arrays.asList(comp(before)));
            parts.addAll(Arrays.asList(TextComponent.fromLegacyText(col(cfg("chat.message-color")) + message)));
            if (!after.isEmpty()) parts.addAll(Arrays.asList(comp(after)));

            ClickEvent click = getConfig().getBoolean("chat.click-enabled", true)
                    ? new ClickEvent(ClickEvent.Action.RUN_COMMAND, OPEN + src.getName()) : null;
            HoverEvent hover = getConfig().getBoolean("chat.hover-enabled", true)
                    ? new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(comp(statsHover(src)))) : null;

            TextComponent out = new TextComponent();
            for (BaseComponent b : parts) {
                if (click != null) b.setClickEvent(click);
                if (hover != null) b.setHoverEvent(hover);
                out.addExtra(b);
            }
            if (click != null) out.setClickEvent(click);
            if (hover != null) out.setHoverEvent(hover);

            for (Player viewer : recipients) if (viewer.isOnline()) viewer.spigot().sendMessage(out);
            Bukkit.getConsoleSender().sendMessage(src.getName() + " » " + message);
        });
    }

    // Przechwytuje ukrytą komendę z kliknięcia - bez permisji, bez "Unknown command"
    @EventHandler(priority = EventPriority.LOWEST)
    public void onHiddenCommand(PlayerCommandPreprocessEvent e) {
        String m = e.getMessage();
        if (!m.regionMatches(true, 0, OPEN, 0, OPEN.length())) return;
        e.setCancelled(true);
        openFor(e.getPlayer(), m.substring(OPEN.length()).trim());
    }

    private long playTicks(Player p) {
        try {
            return p.getStatistic(Statistic.valueOf("PLAY_ONE_MINUTE"));
        } catch (Throwable t) {
            try { return p.getStatistic(Statistic.valueOf("PLAY_TIME")); }
            catch (Throwable t2) { return 0; }
        }
    }

    private String statsHover(Player p) {
        double money = vault != null ? vault.balance(p) : 0;
        int kills = p.getStatistic(Statistic.PLAYER_KILLS);
        int deaths = p.getStatistic(Statistic.DEATHS);
        long sec = playTicks(p) / 20L;
        String time = ph(cfg("stats.time-format"),
                "h", String.valueOf(sec / 3600),
                "m", String.valueOf((sec % 3600) / 60),
                "s", String.valueOf(sec % 60));
        String moneyStr;
        try { moneyStr = String.format(Locale.US, cfg("stats.money-format"), money); }
        catch (Exception ex) { moneyStr = String.valueOf(money); }

        List<String> lines = getConfig().getStringList("chat.hover");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append(ph(lines.get(i),
                    "player", p.getName(),
                    "money", moneyStr,
                    "kills", String.valueOf(kills),
                    "deaths", String.valueOf(deaths),
                    "time", time));
            if (i < lines.size() - 1) sb.append("\n");
        }
        return sb.toString();
    }

    // ---------------- KOMENDY ----------------

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String name = cmd.getName().toLowerCase();

        if (name.equals("galaxytpa")) {
            if (!s.isOp()) { say(s, "no-permission"); return true; }
            if (a.length > 0 && a[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                say(s, "reload-done");
            } else {
                s.sendMessage(col("&7Poprawne użycie&8: &f/galaxytpa reload"));
            }
            return true;
        }

        if (!(s instanceof Player p)) { say(s, "only-players"); return true; }
        switch (name) {
            case "tpa" -> {
                if (a.length < 1) { say(p, "usage-tpa"); return true; }
                Player t = Bukkit.getPlayerExact(a[0]);
                if (t == null) { say(p, "player-not-found"); return true; }
                tpa(p, t);
            }
            case "tpaccept" -> {
                if (a.length < 1) { say(p, "usage-tpaccept"); return true; }
                if (a[0].equals("*")) { tpAcceptAll(p); return true; }
                Player t = Bukkit.getPlayerExact(a[0]);
                if (t == null) { say(p, "player-not-found"); return true; }
                tpAccept(p, t);
            }
            case "tpdeny" -> {
                if (a.length < 1) { say(p, "usage-tpdeny"); return true; }
                Player t = Bukkit.getPlayerExact(a[0]);
                if (t == null) { say(p, "player-not-found"); return true; }
                tpDeny(p, t);
            }
            default -> { }
        }
        return true;
    }

    // p wysyła prośbę do t
    private void tpa(Player p, Player t) {
        if (t.equals(p)) { say(p, "cannot-self"); return; }

        requests.computeIfAbsent(t.getUniqueId(), k -> new HashMap<>())
                .put(p.getUniqueId(), System.currentTimeMillis());
        say(p, "request-sent", "player", t.getName());

        String tpl = cfg("messages.request-received");
        if (tpl.isEmpty()) { sendExtra(t, p); return; }
        TextComponent out = new TextComponent();
        int idx = tpl.indexOf("{player}");
        if (idx < 0) {
            out.addExtra(line(tpl));
        } else {
            out.addExtra(line(tpl.substring(0, idx)));
            out.addExtra(clickable(
                    ph(cfg("messages.request-received-player"), "player", p.getName()),
                    OPEN + p.getName(),
                    ph(cfg("messages.request-received-hover"), "player", p.getName())));
            out.addExtra(line(tpl.substring(idx + 8)));
        }
        t.spigot().sendMessage(out);
        sendExtra(t, p);
    }

    // dodatkowe linie pod powiadomieniem o prośbie (messages.request-received-extra)
    private void sendExtra(Player to, Player from) {
        for (String l : getConfig().getStringList("messages.request-received-extra"))
            to.sendMessage(col(ph(l, "player", from.getName())));
    }

    // p akceptuje prośbę od t (t zostanie przeteleportowany do p)
    private void tpAccept(Player p, Player t) {
        if (!takeRequest(p.getUniqueId(), t.getUniqueId())) {
            say(p, "no-request");
            return;
        }
        int secs = Math.max(0, getConfig().getInt("settings.countdown-seconds", 5));
        say(p, "accepted-self", "player", t.getName());
        say(t, "accepted-other", "player", p.getName());
        say(t, "teleport-soon", "seconds", String.valueOf(secs));
        startCountdown(t, p.getLocation().clone(), secs);
    }

    // /tpaccept * - p akceptuje wszystkie oczekujące prośby naraz
    private void tpAcceptAll(Player p) {
        Map<UUID, Long> m = requests.get(p.getUniqueId());
        if (m == null || m.isEmpty()) { say(p, "no-requests"); return; }

        int secs = Math.max(0, getConfig().getInt("settings.countdown-seconds", 5));
        Location dest = p.getLocation().clone();
        int count = 0;
        for (UUID id : new ArrayList<>(m.keySet())) {
            Player t = Bukkit.getPlayer(id);
            if (t == null) { m.remove(id); continue; }
            if (!takeRequest(p.getUniqueId(), id)) continue;
            say(t, "accepted-other", "player", p.getName());
            say(t, "teleport-soon", "seconds", String.valueOf(secs));
            startCountdown(t, dest, secs);
            count++;
        }
        if (count == 0) say(p, "no-requests");
        else say(p, "accepted-all", "count", String.valueOf(count));
    }

    // p odrzuca prośbę od t
    private void tpDeny(Player p, Player t) {
        if (!takeRequest(p.getUniqueId(), t.getUniqueId())) {
            say(p, "no-request");
            return;
        }
        say(p, "denied-self", "player", t.getName());
        say(t, "denied-other", "player", p.getName());
    }

    // ---------------- ODLICZANIE ----------------

    private void finishTeleport(Player pl, Location dest) {
        pl.teleport(dest);
        String m = cfg("messages.teleported-title");
        subtitle(pl, m);
        actionBar(pl, m);
        say(pl, "teleported-chat");
    }

    private void startCountdown(Player t, Location dest, int total) {
        cancelCountdown(t, false);
        if (total <= 0) { finishTeleport(t, dest); return; }

        UUID id = t.getUniqueId();
        BukkitTask task = new BukkitRunnable() {
            int left = total;
            @Override public void run() {
                Player pl = Bukkit.getPlayer(id);
                if (pl == null) { countdowns.remove(id); cancel(); return; }
                if (left > 0) {
                    String m = ph(cfg("messages.countdown"), "seconds", String.valueOf(left));
                    subtitle(pl, m);
                    actionBar(pl, m);
                    left--;
                } else {
                    countdowns.remove(id);
                    finishTeleport(pl, dest);
                    cancel();
                }
            }
        }.runTaskTimer(this, 0L, 20L);
        countdowns.put(id, task);
    }

    private void cancelCountdown(Player p, boolean notify) {
        BukkitTask t = countdowns.remove(p.getUniqueId());
        if (t == null) return;
        t.cancel();
        if (notify) {
            subtitle(p, cfg("messages.cancelled-title"));
            say(p, "cancelled-chat");
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!countdowns.containsKey(e.getPlayer().getUniqueId())) return;
        if (!getConfig().getBoolean("settings.cancel-on-move", true)) return;
        Location f = e.getFrom(), to = e.getTo();
        if (to == null) return;
        if (f.getBlockX() != to.getBlockX() || f.getBlockY() != to.getBlockY() || f.getBlockZ() != to.getBlockZ())
            cancelCountdown(e.getPlayer(), true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        cancelCountdown(e.getPlayer(), false);
       
        requests.remove(id);
        requests.values().forEach(m -> m.remove(id));
    }

    // ---------------- GUI (wszystko z configu) ----------------

    private void openFor(Player p, String name) {
        Player t = Bukkit.getPlayerExact(name);
        if (t == null) { say(p, "player-not-found"); return; }
        if (t.equals(p)) { say(p, "cannot-self"); return; }

        if (hasRequest(p.getUniqueId(), t.getUniqueId())) openGui(p, t, GuiType.ACCEPT);
        else openGui(p, t, GuiType.SEND);
    }

    private void openGui(Player p, Player other, GuiType type) {
        ConfigurationSection sec = getConfig().getConfigurationSection(
                type == GuiType.SEND ? "gui.send" : "gui.accept");
        if (sec == null) {
            getLogger().warning("Brak sekcji gui w config.yml!");
            return;
        }

        int rows = Math.max(1, Math.min(6, sec.getInt("rows", 4)));
        TpaHolder holder = new TpaHolder(type, other.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, rows * 9,
                col(ph(sec.getString("title", ""), "player", other.getName())));
        holder.inv = inv;

        // tło (opcjonalne)
        String filler = sec.getString("filler", "NONE");
        if (filler != null && !filler.equalsIgnoreCase("NONE") && !filler.isEmpty()) {
            Material fm = Material.matchMaterial(filler);
            if (fm != null && !fm.isAir()) {
                ItemStack fi = new ItemStack(fm);
                ItemMeta fmeta = fi.getItemMeta();
                if (fmeta != null) { fmeta.setDisplayName(" "); fi.setItemMeta(fmeta); }
                for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, fi);
            }
        }

        ConfigurationSection items = sec.getConfigurationSection("items");
        if (items != null) {
            for (String key : items.getKeys(false)) {
                ConfigurationSection is = items.getConfigurationSection(key);
                if (is == null) continue;
                int slot = is.getInt("slot", -1);
                if (slot < 0 || slot >= inv.getSize()) {
                    getLogger().warning("Zły slot dla przedmiotu '" + key + "' w gui!");
                    continue;
                }

                Material m = Material.matchMaterial(is.getString("material", "STONE"));
                if (m == null || m.isAir()) {
                    getLogger().warning("Zły materiał dla przedmiotu '" + key + "' w gui!");
                    m = Material.STONE;
                }

                ItemStack it = new ItemStack(m);
                ItemMeta meta = it.getItemMeta();
                if (meta != null) {
                    // "&r" na początku usuwa domyślną kursywę nazw i opisów
                    meta.setDisplayName(col("&r" + ph(is.getString("name", ""), "player", other.getName())));
                    List<String> lore = new ArrayList<>();
                    for (String l : is.getStringList("lore"))
                        lore.add(col("&r" + ph(l, "player", other.getName())));
                    if (!lore.isEmpty()) meta.setLore(lore);
                    if (meta instanceof SkullMeta sm) sm.setOwningPlayer(other);
                    it.setItemMeta(meta);
                }
                inv.setItem(slot, it);

                holder.actions.put(slot, new String[]{
                        is.getString("left-click", "NONE"),
                        is.getString("right-click", "NONE")});
            }
        }
        p.openInventory(inv);
    }

    private void runAction(Player p, TpaHolder h, String action) {
        Player other = Bukkit.getPlayer(h.other);
        switch (action.toUpperCase(Locale.ROOT)) {
            case "SEND" -> {
                p.closeInventory();
                if (other == null) { say(p, "player-offline"); return; }
                tpa(p, other);
            }
            case "ACCEPT" -> {
                p.closeInventory();
                if (other == null) { say(p, "player-offline"); return; }
                tpAccept(p, other);
            }
            case "DENY" -> {
                p.closeInventory();
                if (other == null) { say(p, "player-offline"); return; }
                tpDeny(p, other);
            }
            case "CLOSE" -> p.closeInventory();
            default -> { }
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof TpaHolder h)) return;
        e.setCancelled(true); // zabezpieczenie przed wyjmowaniem
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        String[] acts = h.actions.get(e.getSlot());
        if (acts == null) return;
        runAction(p, h, e.isRightClick() ? acts[1] : acts[0]);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof TpaHolder) e.setCancelled(true);
    }
}
