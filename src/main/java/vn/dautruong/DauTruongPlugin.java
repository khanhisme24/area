package vn.dautruong;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.BlockState;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class DauTruongPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX = "<gray>[<red><bold>Đấu Trường</bold></red>]</gray> ";

    private record Challenge(UUID from, long expiresAt) {}

    private final Map<UUID, Duel> duels = new HashMap<>();
    private final Map<UUID, Challenge> challenges = new HashMap<>(); // key = người được thách
    final Map<UUID, PlayerSnapshot> pendingRestore = new HashMap<>();
    private final Map<UUID, PlayerSnapshot> spectators = new HashMap<>();
    private boolean internalTeleport;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        var cmd = getCommand("dautruong");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        getServer().getPluginManager().registerEvents(new DuelListener(this), this);
    }

    @Override
    public void onDisable() {
        for (Duel d : new HashSet<>(duels.values())) {
            if (d.task != null) d.task.cancel();
            clearBlocks(d);
            restore(d, d.a);
            restore(d, d.b);
        }
        releaseSpectators();
        duels.clear();
    }

    // ------------------------------------------------------------------ truy vấn

    Duel duelOf(UUID id) {
        return duels.get(id);
    }

    boolean isInternalTeleport() {
        return internalTeleport;
    }

    World arenaWorld() {
        Location l = readLoc("arena.spawn1");
        return l == null ? null : l.getWorld();
    }

    void send(CommandSender to, String mini) {
        to.sendMessage(MM.deserialize(PREFIX + mini));
    }

    // ------------------------------------------------------------------ lệnh

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            send(sender, "<red>Chỉ người chơi mới dùng được lệnh này.");
            return true;
        }
        if (args.length == 0) {
            send(p, "<yellow>/dautruong <tên người chơi></yellow> <gray>- thách đấu");
            send(p, "<yellow>/dautruong accept [tên]</yellow> <gray>- chấp nhận");
            send(p, "<yellow>/dautruong deny [tên]</yellow> <gray>- từ chối");
            send(p, "<yellow>/dautruong xem [tên]</yellow> <gray>- vào xem trận đang diễn ra");
            send(p, "<yellow>/dautruong leave</yellow> <gray>- đầu hàng / thoát chế độ xem");
            if (p.hasPermission("dautruong.admin")) {
                send(p, "<yellow>/dautruong setspawn1|setspawn2|setexit|setspectate</yellow> <gray>- cài đặt đấu trường");
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "accept", "chapnhan" -> accept(p, args.length > 1 ? args[1] : null);
            case "deny", "tuchoi" -> deny(p, args.length > 1 ? args[1] : null);
            case "leave", "thoat", "thoatxem" -> forfeit(p);
            case "spectate", "xem" -> spectate(p, args.length > 1 ? args[1] : null);
            case "setspawn1", "setspawn2", "setexit", "setspectate" -> {
                if (!p.hasPermission("dautruong.admin")) {
                    send(p, "<red>Bạn không có quyền.");
                    return true;
                }
                writeLoc("arena." + sub.substring(3), p.getLocation());
                saveConfig();
                send(p, "<green>Đã lưu vị trí <yellow>" + sub.substring(3) + "</yellow>.");
            }
            default -> challenge(p, args[0]);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("accept", "deny", "leave", "xem"));
            if (sender.hasPermission("dautruong.admin")) out.addAll(List.of("setspawn1", "setspawn2", "setexit", "setspectate"));
            addPlayers(out, sender);
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("accept") || args[0].equalsIgnoreCase("deny") || args[0].equalsIgnoreCase("xem"))) {
            addPlayers(out, sender);
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }

    private void addPlayers(List<String> out, CommandSender except) {
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (!o.equals(except)) out.add(o.getName());
        }
    }

    // ------------------------------------------------------------------ thách đấu

    private boolean arenaReady() {
        Location s1 = readLoc("arena.spawn1");
        Location s2 = readLoc("arena.spawn2");
        return s1 != null && s2 != null && s1.getWorld() == s2.getWorld();
    }

    private void challenge(Player from, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            send(from, "<red>Không tìm thấy người chơi <yellow>" + targetName + "</yellow> đang online.");
            return;
        }
        if (target.equals(from)) {
            send(from, "<red>Bạn không thể tự thách đấu chính mình.");
            return;
        }
        if (spectators.containsKey(from.getUniqueId())) {
            send(from, "<red>Bạn đang xem trận. Dùng <yellow>/dautruong leave</yellow> để thoát chế độ xem trước.");
            return;
        }
        if (spectators.containsKey(target.getUniqueId())) {
            send(from, "<red>Người này đang xem trận.");
            return;
        }
        if (!arenaReady()) {
            send(from, "<red>Đấu trường chưa được cài đặt. Hãy báo admin.");
            return;
        }
        if (duels.containsKey(from.getUniqueId()) || duels.containsKey(target.getUniqueId())) {
            send(from, "<red>Một trong hai người đang trong trận đấu.");
            return;
        }
        if (!duels.isEmpty()) {
            send(from, "<red>Đấu trường đang có trận khác, vui lòng đợi một chút.");
            return;
        }

        long expire = getConfig().getLong("challenge-expire-seconds", 30) * 1000L;
        challenges.put(target.getUniqueId(), new Challenge(from.getUniqueId(), System.currentTimeMillis() + expire));

        send(from, "<green>Đã gửi lời thách đấu tới <yellow>" + target.getName() + "</yellow>.");
        target.sendMessage(MM.deserialize(PREFIX + "<yellow>" + from.getName()
                + "</yellow> <white>thách bạn đấu giao hữu (không mất đồ)! "
                + "<click:run_command:'/dautruong accept " + from.getName() + "'><green><bold>[CHẤP NHẬN]</bold></green></click> "
                + "<click:run_command:'/dautruong deny " + from.getName() + "'><red><bold>[TỪ CHỐI]</bold></red></click>"));
    }

    private Challenge takeChallenge(Player target, String fromName) {
        Challenge c = challenges.get(target.getUniqueId());
        if (c == null || c.expiresAt() < System.currentTimeMillis()) {
            challenges.remove(target.getUniqueId());
            send(target, "<red>Bạn không có lời thách đấu nào (hoặc đã hết hạn).");
            return null;
        }
        if (fromName != null) {
            Player from = Bukkit.getPlayer(c.from());
            if (from == null || !from.getName().equalsIgnoreCase(fromName)) {
                send(target, "<red>Không có lời thách đấu nào từ <yellow>" + fromName + "</yellow>.");
                return null;
            }
        }
        challenges.remove(target.getUniqueId());
        return c;
    }

    private void accept(Player target, String fromName) {
        Challenge c = takeChallenge(target, fromName);
        if (c == null) return;
        Player from = Bukkit.getPlayer(c.from());
        if (from == null) {
            send(target, "<red>Người thách đấu đã thoát game.");
            return;
        }
        if (duels.containsKey(from.getUniqueId()) || duels.containsKey(target.getUniqueId())) {
            send(target, "<red>Một trong hai người đang trong trận đấu.");
            return;
        }
        if (!duels.isEmpty()) {
            send(target, "<red>Đấu trường đang có trận khác, vui lòng đợi một chút.");
            return;
        }
        if (!arenaReady()) {
            send(target, "<red>Đấu trường chưa được cài đặt.");
            return;
        }
        if (spectators.containsKey(from.getUniqueId()) || spectators.containsKey(target.getUniqueId())) {
            send(target, "<red>Một trong hai người đang xem trận.");
            return;
        }
        startDuel(from, target);
    }

    private void deny(Player target, String fromName) {
        Challenge c = takeChallenge(target, fromName);
        if (c == null) return;
        send(target, "<gray>Bạn đã từ chối lời thách đấu.");
        Player from = Bukkit.getPlayer(c.from());
        if (from != null) send(from, "<yellow>" + target.getName() + "</yellow> <red>đã từ chối</red> lời thách đấu.");
    }

    void forfeit(Player p) {
        PlayerSnapshot watching = spectators.remove(p.getUniqueId());
        if (watching != null) {
            applySnapshot(p, watching);
            send(p, "<gray>Bạn đã thoát chế độ xem.");
            return;
        }
        Duel d = duels.get(p.getUniqueId());
        if (d == null || d.state == Duel.State.ENDED) {
            send(p, "<red>Bạn không ở trong trận đấu nào.");
            return;
        }
        endDuel(d, d.opponent(p.getUniqueId()), p.getUniqueId(), "đầu hàng");
    }

    // ------------------------------------------------------------------ trận đấu

    private void startDuel(Player a, Player b) {
        Location s1 = readLoc("arena.spawn1");
        Location s2 = readLoc("arena.spawn2");

        Duel d = new Duel(a.getUniqueId(), b.getUniqueId());
        duels.put(a.getUniqueId(), d);
        duels.put(b.getUniqueId(), d);

        // Chụp lại đồ + trạng thái TRƯỚC khi làm bất cứ điều gì khác
        d.snapshots.put(a.getUniqueId(), PlayerSnapshot.capture(a));
        d.snapshots.put(b.getUniqueId(), PlayerSnapshot.capture(b));

        prepare(a);
        prepare(b);
        tp(a, s1);
        tp(b, s2);

        int seconds = Math.max(1, getConfig().getInt("countdown-seconds", 5));
        d.task = new BukkitRunnable() {
            int left = seconds;

            @Override
            public void run() {
                Player pa = Bukkit.getPlayer(d.a);
                Player pb = Bukkit.getPlayer(d.b);
                if (pa == null || pb == null || d.state != Duel.State.COUNTDOWN) {
                    cancel();
                    return;
                }
                if (left <= 0) {
                    d.state = Duel.State.FIGHTING;
                    for (Player p : new Player[]{pa, pb}) {
                        title(p, "<red><bold>CHIẾN!", "<gray>Chúc may mắn");
                        p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1f);
                    }
                    cancel();
                    return;
                }
                for (Player p : new Player[]{pa, pb}) {
                    title(p, "<yellow><bold>" + left, "<gray>Chuẩn bị...");
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f);
                }
                left--;
            }
        }.runTaskTimer(this, 0L, 20L);

        send(a, "<green>Trận đấu giao hữu với <yellow>" + b.getName() + "</yellow> sắp bắt đầu. Đồ của bạn sẽ được trả lại nguyên vẹn sau trận.");
        send(b, "<green>Trận đấu giao hữu với <yellow>" + a.getName() + "</yellow> sắp bắt đầu. Đồ của bạn sẽ được trả lại nguyên vẹn sau trận.");
    }

    void endDuel(Duel d, UUID winner, UUID loser, String reason) {
        if (d.state == Duel.State.ENDED) return;
        d.state = Duel.State.ENDED;
        if (d.task != null) d.task.cancel();

        Player w = Bukkit.getPlayer(winner);
        Player l = Bukkit.getPlayer(loser);
        String wn = w != null ? w.getName() : nameOf(winner);
        String ln = l != null ? l.getName() : nameOf(loser);
        String line = "<yellow>" + wn + "</yellow> <green>đã thắng</green> <yellow>" + ln + "</yellow> <gray>(" + reason + ")";

        if (w != null) {
            send(w, line);
            title(w, "<green><bold>CHIẾN THẮNG!", "<gray>Đồ sẽ được trả lại sau giây lát");
        }
        if (l != null) {
            send(l, line);
            title(l, "<red><bold>THUA CUỘC", "<gray>Đồ sẽ được trả lại sau giây lát");
        }

        // Trả đồ + đưa về chỗ cũ sau 3 giây (trong lúc đó không ai nhận sát thương)
        Bukkit.getScheduler().runTaskLater(this, () -> {
            clearBlocks(d);
            releaseSpectators();
            restore(d, winner);
            restore(d, loser);
            duels.remove(d.a);
            duels.remove(d.b);
        }, 60L);
    }

    /** Xóa toàn bộ block người chơi đã đặt, trả lại đúng block gốc của đấu trường. */
    void clearBlocks(Duel d) {
        for (BlockState original : d.placed.values()) {
            original.update(true, false);
        }
        d.placed.clear();
    }

    void restore(Duel d, UUID id) {
        PlayerSnapshot s = d.snapshots.remove(id);
        if (s == null) return;
        Player p = Bukkit.getPlayer(id);
        if (p == null) return;
        if (p.isDead()) { // chờ respawn rồi trả sau
            pendingRestore.put(id, s);
            return;
        }
        applySnapshot(p, s);
    }

    void applySnapshot(Player p, PlayerSnapshot s) {
        internalTeleport = true;
        try {
            s.restore(p);
        } finally {
            internalTeleport = false;
        }
    }

    void handleQuit(Player p) {
        UUID id = p.getUniqueId();
        challenges.remove(id);
        PlayerSnapshot watching = spectators.remove(id);
        if (watching != null) applySnapshot(p, watching);
        Duel d = duels.get(id);
        if (d == null) return;
        if (d.state != Duel.State.ENDED) {
            endDuel(d, d.opponent(id), id, "đã thoát game");
        }
        restore(d, id); // trả đồ ngay khi người này thoát
    }

    void handleJoin(Player p) {
        UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            PlayerSnapshot s = pendingRestore.remove(id);
            if (s != null) {
                applySnapshot(p, s);
                return;
            }
            // Phòng trường hợp server sập giữa trận: kéo người chơi ra khỏi đấu trường
            World arena = arenaWorld();
            if (arena != null && p.getWorld() == arena && !duels.containsKey(id)
                    && !spectators.containsKey(id) && !p.hasPermission("dautruong.admin")) {
                if (p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(GameMode.SURVIVAL);
                Location exit = readLoc("arena.exit");
                if (exit == null) exit = Bukkit.getWorlds().get(0).getSpawnLocation();
                tp(p, exit);
            }
        }, 5L);
    }

    // ------------------------------------------------------------------ khán giả

    boolean isSpectating(Player p) {
        return spectators.containsKey(p.getUniqueId());
    }

    private void spectate(Player p, String targetName) {
        UUID id = p.getUniqueId();
        if (duels.containsKey(id)) {
            send(p, "<red>Bạn đang trong trận đấu.");
            return;
        }
        if (spectators.containsKey(id)) {
            send(p, "<red>Bạn đang xem rồi. Dùng <yellow>/dautruong leave</yellow> để thoát.");
            return;
        }
        Duel d = duels.values().stream().filter(x -> x.state != Duel.State.ENDED).findFirst().orElse(null);
        if (d == null) {
            send(p, "<red>Hiện không có trận đấu nào để xem.");
            return;
        }
        Player target = null;
        if (targetName != null) {
            target = Bukkit.getPlayerExact(targetName);
            if (target == null || duels.get(target.getUniqueId()) != d) {
                send(p, "<red>Người này không ở trong trận đang diễn ra.");
                return;
            }
        }
        Location dest = target != null ? target.getLocation() : readLoc("arena.spectate");
        if (dest == null) dest = readLoc("arena.spawn1");
        if (dest == null) {
            send(p, "<red>Đấu trường chưa được cài đặt.");
            return;
        }
        spectators.put(id, PlayerSnapshot.capture(p));
        p.closeInventory();
        p.setGameMode(GameMode.SPECTATOR);
        tp(p, dest);
        send(p, "<green>Bạn đang xem trận. Gõ <yellow>/dautruong leave</yellow> để thoát.");
    }

    private void releaseSpectators() {
        for (UUID id : new ArrayList<>(spectators.keySet())) {
            PlayerSnapshot s = spectators.remove(id);
            Player p = Bukkit.getPlayer(id);
            if (p != null && s != null) {
                applySnapshot(p, s);
                send(p, "<gray>Trận đấu đã kết thúc.");
            }
        }
    }

    // ------------------------------------------------------------------ tiện ích

    private void prepare(Player p) {
        p.closeInventory();
        p.setGameMode(GameMode.SURVIVAL);
        for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) {
            p.removePotionEffect(e.getType());
        }
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(max == null ? 20.0 : max.getValue());
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
    }

    private void tp(Player p, Location l) {
        internalTeleport = true;
        try {
            p.teleport(l);
        } finally {
            internalTeleport = false;
        }
    }

    private void title(Player p, String main, String sub) {
        p.showTitle(Title.title(MM.deserialize(main), MM.deserialize(sub),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ofMillis(100))));
    }

    private String nameOf(UUID id) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? "???" : n;
    }

    private void writeLoc(String path, Location l) {
        getConfig().set(path + ".world", l.getWorld().getKey().asString());
        getConfig().set(path + ".x", l.getX());
        getConfig().set(path + ".y", l.getY());
        getConfig().set(path + ".z", l.getZ());
        getConfig().set(path + ".yaw", (double) l.getYaw());
        getConfig().set(path + ".pitch", (double) l.getPitch());
    }

    private Location readLoc(String path) {
        ConfigurationSection s = getConfig().getConfigurationSection(path);
        if (s == null) return null;
        NamespacedKey key = NamespacedKey.fromString(s.getString("world", ""));
        if (key == null) return null;
        World w = Bukkit.getWorld(key);
        if (w == null) return null;
        return new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
    }
}
