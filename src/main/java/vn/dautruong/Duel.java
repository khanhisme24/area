package vn.dautruong;

import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class Duel {

    enum State { COUNTDOWN, FIGHTING, ENDED }

    final UUID a;
    final UUID b;
    final Map<UUID, PlayerSnapshot> snapshots = new HashMap<>();
    State state = State.COUNTDOWN;
    BukkitTask task;

    Duel(UUID a, UUID b) {
        this.a = a;
        this.b = b;
    }

    UUID opponent(UUID id) {
        return id.equals(a) ? b : a;
    }
}
