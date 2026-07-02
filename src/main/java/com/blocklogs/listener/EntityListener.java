package com.blocklogs.listener;

import com.blocklogs.BlockLogsServices;
import com.blocklogs.api.action.ActionType;
import com.blocklogs.api.actor.Actor;
import com.blocklogs.api.model.WorldPos;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Records player-attributable entity/animal actions and item drop/pickup: killing a mob, spawning one
 * through a spawner egg or breeding, shearing, breeding, taming, leashing/unleashing, and dropping or
 * picking up an item. Every handler enqueues cheaply through
 * {@link com.blocklogs.core.logging.LoggingService} — no I/O happens on the main thread.
 *
 * <p>Only actions attributable to a player are logged. Natural mob spawns and mob-on-mob kills would
 * flood the log and carry no responsible player, so they are skipped. Entity actions are gated on
 * {@code logEntities()}, item actions on {@code logItems()}, and everything on the per-world toggle.
 */
public final class EntityListener implements Listener {

    private final BlockLogsServices services;
    private final LogSupport support;

    public EntityListener(BlockLogsServices services) {
        this.services = services;
        this.support = new LogSupport(services);
    }

    // --- entity actions (gated on logEntities) ---

    @EventHandler(ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!services.config().logEntities()) {
            return;
        }
        LivingEntity dead = event.getEntity();
        // Only attribute kills to a real player; mob-on-mob and environmental deaths are skipped.
        Player killer = dead.getKiller();
        if (killer == null) {
            return;
        }
        if (!support.worldEnabled(dead.getWorld().getName())) {
            return;
        }
        record(LogSupport.actor(killer), ActionType.ENTITY_KILL, dead, null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!services.config().logEntities()) {
            return;
        }
        Entity entity = event.getEntity();
        if (!support.worldEnabled(entity.getWorld().getName())) {
            return;
        }
        // Only player-attributable spawn reasons; natural/structure spawns would flood the log.
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        switch (reason) {
            case SPAWNER_EGG, DISPENSE_EGG, BUILD_SNOWMAN, BUILD_IRONGOLEM, BUILD_WITHER -> {
                // Bukkit does not expose the player behind these spawns on this event, so the concrete
                // actor is unavailable here. Attribute to a natural marker tagged with the reason; a
                // future part could correlate with a recent interaction to recover the player.
                // TODO: resolve the responsible player (e.g. via recent PlayerInteractEvent) if needed.
                Actor actor = Actor.natural("#spawn:" + reason.name().toLowerCase());
                record(actor, ActionType.ENTITY_SPAWN, entity, reason.name().toLowerCase());
            }
            default -> {
                // BREEDING is handled by EntityBreedEvent (which carries the breeder); everything else
                // is natural and intentionally ignored.
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) {
        if (!services.config().logEntities()) {
            return;
        }
        Entity entity = event.getEntity();
        if (!support.worldEnabled(entity.getWorld().getName())) {
            return;
        }
        record(LogSupport.actor(event.getPlayer()), ActionType.ENTITY_MODIFY, entity, "shear");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (!services.config().logEntities()) {
            return;
        }
        // Only log breeding driven by a player; mobs bred by other means carry no responsible player.
        if (!(event.getBreeder() instanceof Player breeder)) {
            return;
        }
        Entity child = event.getEntity();
        if (!support.worldEnabled(child.getWorld().getName())) {
            return;
        }
        // Log the newly born child as a spawn attributed to the breeder.
        record(LogSupport.actor(breeder), ActionType.ENTITY_SPAWN, child, "breed");
    }

    @EventHandler(ignoreCancelled = true)
    public void onTame(EntityTameEvent event) {
        if (!services.config().logEntities()) {
            return;
        }
        AnimalTamer owner = event.getOwner();
        if (!(owner instanceof Player player)) {
            return;
        }
        LivingEntity entity = event.getEntity();
        if (!support.worldEnabled(entity.getWorld().getName())) {
            return;
        }
        record(LogSupport.actor(player), ActionType.ENTITY_MODIFY, entity, "tame");
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeash(PlayerLeashEntityEvent event) {
        if (!services.config().logEntities()) {
            return;
        }
        Entity entity = event.getEntity();
        if (!support.worldEnabled(entity.getWorld().getName())) {
            return;
        }
        record(LogSupport.actor(event.getPlayer()), ActionType.ENTITY_MODIFY, entity, "leash");
    }

    @EventHandler(ignoreCancelled = true)
    public void onUnleash(PlayerUnleashEntityEvent event) {
        if (!services.config().logEntities()) {
            return;
        }
        Entity entity = event.getEntity();
        if (!support.worldEnabled(entity.getWorld().getName())) {
            return;
        }
        record(LogSupport.actor(event.getPlayer()), ActionType.ENTITY_MODIFY, entity, "unleash");
    }

    // TODO: dyeing a sheep via PlayerInteractEntityEvent -> ENTITY_MODIFY, detail="dye:"+color.
    // The dye-material -> DyeColor mapping and the "is this a dyeable target" check are fiddly across
    // versions (and the interaction also fires for non-dye held items), so it is left out for now rather
    // than logging false positives. Add here when a reliable color resolution is available.

    // --- item actions (gated on logItems) ---

    @EventHandler(ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event) {
        if (!services.config().logItems()) {
            return;
        }
        Item drop = event.getItemDrop();
        if (!support.worldEnabled(drop.getWorld().getName())) {
            return;
        }
        ItemStack stack = drop.getItemStack();
        recordItem(LogSupport.actor(event.getPlayer()), ActionType.ITEM_DROP, drop, stack);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickupItem(EntityPickupItemEvent event) {
        if (!services.config().logItems()) {
            return;
        }
        // Only player pickups; mob pickups are not player-attributable.
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Item item = event.getItem();
        if (!support.worldEnabled(item.getWorld().getName())) {
            return;
        }
        ItemStack stack = item.getItemStack();
        recordItem(LogSupport.actor(player), ActionType.ITEM_PICKUP, item, stack);
    }

    // --- helpers ---

    /** Record an entity payload for the given entity at its current location. */
    private void record(Actor actor, ActionType action, Entity entity, String detail) {
        String entityTypeKey = entity.getType().getKey().asString();
        UUID entityUuid = entity.getUniqueId();
        WorldPos pos = LogSupport.pos(entity.getLocation());
        services.logging().record(actor, action, pos)
                .entity(entityTypeKey, entityUuid, detail)
                .commit();
    }

    /**
     * Record an item drop/pickup as an entity payload on the dropped-item entity, with the item key and
     * amount carried in the detail field (e.g. {@code minecraft:diamond x3}).
     */
    private void recordItem(Actor actor, ActionType action, Item itemEntity, ItemStack stack) {
        String itemKey = stack.getType().getKey().asString();
        String detail = itemKey + " x" + stack.getAmount();
        WorldPos pos = LogSupport.pos(itemEntity.getLocation());
        services.logging().record(actor, action, pos)
                .entity("minecraft:item", itemEntity.getUniqueId(), detail)
                .commit();
    }
}
