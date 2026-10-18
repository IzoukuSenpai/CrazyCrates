package com.badbones69.crazycrates.paper.api.builders.gui;

import com.badbones69.crazycrates.paper.CrazyCrates;
import com.badbones69.crazycrates.paper.api.CrazyCratesPaper;
import com.badbones69.crazycrates.paper.api.enums.other.Plugins;
import com.badbones69.crazycrates.paper.api.objects.Crate;
import com.badbones69.crazycrates.paper.cache.CacheManager;
import com.badbones69.crazycrates.paper.managers.BukkitUserManager;
import com.badbones69.crazycrates.paper.managers.InventoryManager;
import com.badbones69.crazycrates.paper.tasks.crates.CrateManager;
import com.ryderbelserion.fusion.core.utils.StringUtils;
import com.ryderbelserion.fusion.paper.FusionPaper;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import us.crazycrew.crazycrates.api.config.impl.ConfigManager;
import us.crazycrew.crazycrates.api.config.properties.PropertyManager;
import java.util.Map;
import java.util.UUID;

public abstract class InventoryBuilder {

    protected final Player player;

    public InventoryBuilder(@NotNull final Player player) {
        this.player = player;
    }

    protected final CrazyCrates plugin = CrazyCrates.getPlugin();

    protected final CrazyCratesPaper platform = this.plugin.getPlatform();

    protected final ConfigManager configManager = this.platform.getConfigManager();

    protected final PropertyManager pluginConfig = this.configManager.getConfig();

    protected final FusionPaper fusion = this.platform.getFusion();

    protected final CrateManager crateManager = this.platform.getCrateManager();

    protected final BukkitUserManager userManager = this.platform.getUserManager();

    protected final CacheManager cacheManager = this.platform.getCacheManager();

    protected final InventoryManager inventoryManager = this.platform.getInventoryManager();

    public final String parse(@NotNull final Player player, @NotNull final String title) {
        return Plugins.placeholder_api.isEnabled() ? PlaceholderAPI.setPlaceholders(player, title) : title;
    }

    public final String getCrates(@NotNull final String option) {
        if (option.isEmpty()) return "";

        final UUID uuid = this.player.getUniqueId();

        String safe = option;

        for (final Crate crate : this.crateManager.getUsableCrates()) {
            final String fileName = crate.getFileName();
            final String crateName = fileName.toLowerCase();

            final int virtual = this.userManager.getVirtualKeys(uuid, fileName);
            final int physical = this.userManager.getPhysicalKeys(uuid, fileName);

            final int total = virtual + physical;

            final int opened = this.userManager.getCrateOpened(uuid, fileName);

            safe = this.fusion.replacePlaceholders(safe, Map.of(
                    "%{}%".replace("{}", crateName), StringUtils.formatNumber(virtual),
                    "%{}_physical%".replace("{}", crateName), StringUtils.format(physical),
                    "%{}_total%".replace("{}", crateName), StringUtils.format(total),
                    "%{}_opened%".replace("{}", crateName), StringUtils.format(opened),
                    "%{}_raw%".replace("{}", crateName), String.valueOf(virtual),
                    "%{}_raw_physical%".replace("{}", crateName), String.valueOf(physical),
                    "%{}_raw_total%".replace("{}", crateName), String.valueOf(total),
                    "%{}_raw_opened%".replace("{}", crateName), String.valueOf(opened)
            ));
        }

        return safe;
    }
}