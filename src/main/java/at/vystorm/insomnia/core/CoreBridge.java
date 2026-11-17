package at.vystorm.insomnia.core;

import Vystorm.vystorm_core.API;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.LocalDateTime;
import java.util.Objects;

public final class CoreBridge {
    private final API api;

    public CoreBridge(API api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    public API api() { return api; }

    public void mid(String message) {
        api.debug().mid("[VystormInsomnia] " + message, LocalDateTime.now());
    }

    public void high(String message) {
        api.debug().high("[VystormInsomnia] " + message, LocalDateTime.now());
    }

    public String getNbt(ItemStack item, String key) {
        return item == null ? null : api.nbt().getNBT(item, key);
    }

    public void setNbt(Entity entity, String key, String value) {
        if (entity != null) api.nbt().setNBT(entity, key, value);
    }

    public String getNbt(Entity entity, String key) {
        return entity == null ? null : api.nbt().getNBT(entity, key);
    }

    public <T> void particle(Player viewer, Particle particle, Location location, int count, T data) {
        api.particle().spawn(viewer, particle, location, count, data);
    }
}
