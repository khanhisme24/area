package vn.dautruong;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Locale;

final class DuelListener implements Listener {

    private final DauTruongPlugin plugin;

    DuelListener(DauTruongPlugin plugin) {
        this.plugin = plugin;
    }

    private Duel duel(Player p) {
        return plugin.duelOf(p.getUniqueId());
    }

    private static boolean hasTotem(Player p) {
        return p.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || p.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
    }

    private static Player attackerOf(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p) return p;
        if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }

    // Chỉ cho đánh nhau giữa 2 người trong cùng trận và khi trận đã bắt đầu
    @EventHandler(priority = EventPriority.LOW)
    public void onPvp(EntityDamageByEntityEvent e) {
        Player attacker = attackerOf(e);
        if (attacker == null) return;
        Duel da = duel(attacker);
        Duel dv = e.getEntity() instanceof Player v ? duel(v) : null;
        if (da == null && dv == null) return;
        if (e.getEntity() instanceof Player v && v.equals(attacker)) return;
        if (!(e.getEntity() instanceof Player)) return; // đánh mob: cho phép
        if (da != dv || da.state != Duel.State.FIGHTING) e.setCancelled(true);
    }

    // Chặn cú đánh chí mạng -> không bao giờ chết thật, nên không rớt đồ
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Duel d = duel(p);
        if (d == null) return;
        if (d.state != Duel.State.FIGHTING) {
            e.setCancelled(true);
            return;
        }
        if (e.getFinalDamage() >= p.getHealth() && !hasTotem(p)) {
            e.setCancelled(true);
            plugin.endDuel(d, d.opponent(p.getUniqueId()), p.getUniqueId(), "đã bị hạ gục");
        }
    }

    // Phòng hờ: nếu vẫn lỡ chết thì giữ đồ, không rớt gì
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        Duel d = duel(p);
        if (d == null) return;
        e.setKeepInventory(true);
        e.setKeepLevel(true);
        e.getDrops().clear();
        e.setDroppedExp(0);
        e.deathMessage(null);
        if (d.state != Duel.State.ENDED) {
            plugin.endDuel(d, d.opponent(p.getUniqueId()), p.getUniqueId(), "đã bị hạ gục");
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        PlayerSnapshot s = plugin.pendingRestore.remove(e.getPlayer().getUniqueId());
        if (s == null) return;
        e.setRespawnLocation(s.location());
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> plugin.applySnapshot(p, s));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.handleQuit(e.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        plugin.handleJoin(e.getPlayer());
    }

    // Đóng băng vị trí trong lúc đếm ngược
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Duel d = duel(e.getPlayer());
        if (d == null || d.state != Duel.State.COUNTDOWN) return;
        Location f = e.getFrom();
        Location t = e.getTo();
        if (t == null) return;
        if (f.getX() != t.getX() || f.getZ() != t.getZ()) {
            e.setTo(new Location(f.getWorld(), f.getX(), t.getY(), f.getZ(), t.getYaw(), t.getPitch()));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (duel(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (duel(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (duel(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && duel(p) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (duel(p) == null || p.hasPermission("dautruong.admin")) return;
        String label = e.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (label.equals("dautruong")) return;
        e.setCancelled(true);
        plugin.send(p, "<red>Bạn không thể dùng lệnh khác trong trận. Dùng <yellow>/dautruong leave</yellow> để rời trận.");
    }

    // Không cho dịch chuyển ra khỏi đấu trường trong lúc đang đấu
    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (duel(p) == null || plugin.isInternalTeleport()) return;
        World arena = plugin.arenaWorld();
        Location to = e.getTo();
        if (arena == null || to.getWorld() != arena) e.setCancelled(true);
    }
}
