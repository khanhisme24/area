package vn.dautruong;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.List;

/** Ảnh chụp trạng thái người chơi trước trận để trả lại nguyên vẹn sau trận. */
final class PlayerSnapshot {

    private final Location location;
    private final ItemStack[] contents;
    private final double health;
    private final int food;
    private final float saturation;
    private final float exhaustion;
    private final int level;
    private final float exp;
    private final GameMode gameMode;
    private final int fireTicks;
    private final List<PotionEffect> effects;
    private final boolean allowFlight;
    private final boolean flying;

    private PlayerSnapshot(Player p) {
        this.location = p.getLocation().clone();
        ItemStack[] src = p.getInventory().getContents();
        this.contents = new ItemStack[src.length];
        for (int i = 0; i < src.length; i++) {
            this.contents[i] = src[i] == null ? null : src[i].clone();
        }
        this.health = p.getHealth();
        this.food = p.getFoodLevel();
        this.saturation = p.getSaturation();
        this.exhaustion = p.getExhaustion();
        this.level = p.getLevel();
        this.exp = p.getExp();
        this.gameMode = p.getGameMode();
        this.fireTicks = p.getFireTicks();
        this.effects = new ArrayList<>(p.getActivePotionEffects());
        this.allowFlight = p.getAllowFlight();
        this.flying = p.isFlying();
    }

    static PlayerSnapshot capture(Player p) {
        return new PlayerSnapshot(p);
    }

    Location location() {
        return location.clone();
    }

    void restore(Player p) {
        p.closeInventory();
        p.teleport(location);
        p.setGameMode(gameMode);
        p.getInventory().setContents(contents);

        for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) {
            p.removePotionEffect(e.getType());
        }
        p.addPotionEffects(effects);

        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = max == null ? 20.0 : max.getValue();
        p.setHealth(Math.max(0.5, Math.min(health, maxHealth)));

        p.setFoodLevel(food);
        p.setSaturation(saturation);
        p.setExhaustion(exhaustion);
        p.setLevel(level);
        p.setExp(exp);
        p.setFireTicks(fireTicks);
        p.setFallDistance(0f);
        p.setAllowFlight(allowFlight);
        p.setFlying(allowFlight && flying);
        p.updateInventory();
    }
}
