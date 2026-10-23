package dev.tridentplus.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.tridentplus.TridentPlusPlugin;
import dev.tridentplus.debug.SelfCheck;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.entity.Husk;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class TridentPlusCommand {

    private TridentPlusCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(final TridentPlusPlugin plugin) {
        final LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("tridentplus")
                .requires(source -> source.getSender().hasPermission("tridentplus.admin"));

        root.then(Commands.literal("reload").executes(ctx -> {
            plugin.reloadSettings();
            plugin.resourcePack().reload();
            final CommandSender sender = ctx.getSource().getSender();
            sender.sendMessage(Component.text("[TridentPlus] config.yml reloaded."));
            sender.sendMessage(Component.text(
                    "Live: all damage, Fork selection, loot injection and the resource-pack "
                            + "host/port/prompt."));
            sender.sendMessage(Component.text(
                    "Restart required: Fork fallback name, Fork registration/tags and the "
                            + "Sharpness/Looting item sets (registry-level). The pack's lang "
                            + "files are baked into the jar, so changing the translations "
                            + "requires rebuilding the plugin."));
            return Command.SINGLE_SUCCESS;
        }));

        root.then(Commands.literal("debug")
                .then(Commands.literal("on").executes(ctx -> setDebug(plugin, ctx.getSource().getSender(), true)))
                .then(Commands.literal("off").executes(ctx -> setDebug(plugin, ctx.getSource().getSender(), false))));

        root.then(Commands.literal("give").executes(ctx -> {
            if (!(ctx.getSource().getSender() instanceof Player player)) {
                ctx.getSource().getSender().sendMessage(Component.text("Players only."));
                return 0;
            }
            giveLoadout(plugin, player);
            return Command.SINGLE_SUCCESS;
        }));

        root.then(Commands.literal("dummy")
                .executes(ctx -> spawnDummies(plugin, ctx.getSource().getSender(), 10, false))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                        .executes(ctx -> spawnDummies(plugin, ctx.getSource().getSender(),
                                IntegerArgumentType.getInteger(ctx, "count"), false))
                        .then(Commands.literal("armored").executes(ctx -> spawnDummies(plugin,
                                ctx.getSource().getSender(),
                                IntegerArgumentType.getInteger(ctx, "count"), true)))));

        root.then(Commands.literal("selfcheck").executes(ctx -> {
            final SelfCheck.Result result = new SelfCheck(plugin).run();
            final CommandSender sender = ctx.getSource().getSender();
            sender.sendMessage(Component.text("[TridentPlus] selfcheck: "
                    + (result.passed() ? "ALL PASS" : "FAIL")));
            for (final String line : result.lines()) {
                sender.sendMessage(Component.text(line));
            }
            return Command.SINGLE_SUCCESS;
        }));

        return root.build();
    }

    private static int setDebug(final TridentPlusPlugin plugin,
                                final CommandSender sender,
                                final boolean enabled) {
        if (sender instanceof Player player) {
            plugin.debug().set(player, enabled);
            sender.sendMessage(Component.text("[TridentPlus] debug " + (enabled ? "on" : "off")
                    + " for you."));
        } else {
            sender.sendMessage(Component.text(
                    "Console cannot toggle per-player debug; use the command in game."));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static void giveLoadout(final TridentPlusPlugin plugin, final Player player) {
        final ItemStack trident = new ItemStack(Material.TRIDENT);
        final ItemMeta tridentMeta = trident.getItemMeta();
        if (tridentMeta != null) {
            addEnchant(tridentMeta, Enchantment.SHARPNESS, 5);
            addEnchant(tridentMeta, Enchantment.IMPALING, 5);
            addEnchant(tridentMeta, Enchantment.LOYALTY, 3);
            addEnchant(tridentMeta, Enchantment.UNBREAKING, 3);
            addEnchant(tridentMeta, Enchantment.MENDING, 1);
            addEnchant(tridentMeta, Enchantment.LOOTING, 3);
            final Enchantment fork = plugin.forkEnchantment();
            if (fork != null) {
                addEnchant(tridentMeta, fork, 3);
            }
            trident.setItemMeta(tridentMeta);
        }

        final ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        final ItemMeta swordMeta = sword.getItemMeta();
        if (swordMeta != null) {
            addEnchant(swordMeta, Enchantment.SHARPNESS, 5);
            sword.setItemMeta(swordMeta);
        }

        giveOrDrop(player, trident);
        giveOrDrop(player, sword);
        player.sendMessage(Component.text("[TridentPlus] Test trident and netherite sword given."));
    }

    private static void addEnchant(final ItemMeta meta, final Enchantment enchantment,
                                   final int level) {
        // ignoreLevelRestriction so /tridentplus give also works if max-level config is
        // changed later; the registry still enforces compatibility.
        meta.addEnchant(enchantment, level, true);
    }

    private static int spawnDummies(final TridentPlusPlugin plugin,
                                    final CommandSender sender,
                                    final int count,
                                    final boolean armored) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only."));
            return 0;
        }
        final World world = player.getWorld();
        final org.bukkit.util.Vector forward = player.getLocation().getDirection().setY(0);
        if (forward.lengthSquared() < 1.0e-6) {
            forward.setX(0).setY(0).setZ(1);
        } else {
            forward.normalize();
        }
        final Location center = player.getLocation().clone().add(forward.multiply(2.5));
        for (int i = 0; i < count; i++) {
            final double angle = i * 2.399963229728653;
            final double radius = 0.15 + 0.12 * Math.sqrt(i);
            final Location location = center.clone().add(
                    Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius);
            final Husk husk = world.spawn(location, Husk.class,
                    CreatureSpawnEvent.SpawnReason.CUSTOM, false, entity -> {
                        entity.setAI(false);
                        entity.setSilent(true);
                        entity.setPersistent(true);
                        entity.setRemoveWhenFarAway(false);
                        entity.setCollidable(false);
                        entity.customName(Component.text("0.000"));
                        entity.setCustomNameVisible(true);
                        final var maxHealth = entity.getAttribute(Attribute.MAX_HEALTH);
                        if (maxHealth != null) {
                            maxHealth.setBaseValue(1024.0);
                        }
                        entity.setHealth(1024.0);
                        final EntityEquipment equipment = entity.getEquipment();
                        if (equipment != null) {
                            if (armored) {
                                equipment.setHelmet(new ItemStack(Material.NETHERITE_HELMET));
                                equipment.setChestplate(new ItemStack(Material.NETHERITE_CHESTPLATE));
                                equipment.setLeggings(new ItemStack(Material.NETHERITE_LEGGINGS));
                                equipment.setBoots(new ItemStack(Material.NETHERITE_BOOTS));
                            } else {
                                equipment.clear();
                            }
                            equipment.setHelmetDropChance(0.0f);
                            equipment.setChestplateDropChance(0.0f);
                            equipment.setLeggingsDropChance(0.0f);
                            equipment.setBootsDropChance(0.0f);
                        }
                    });
            plugin.markDummy(husk);
        }
        player.sendMessage(Component.text(String.format(Locale.ROOT,
                "[TridentPlus] Spawned %d husk%s%s.", count, count == 1 ? "" : "s",
                armored ? " with netherite armor" : "")));
        return Command.SINGLE_SUCCESS;
    }

    private static void giveOrDrop(final Player player, final ItemStack stack) {
        player.getInventory().addItem(stack).values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

}
