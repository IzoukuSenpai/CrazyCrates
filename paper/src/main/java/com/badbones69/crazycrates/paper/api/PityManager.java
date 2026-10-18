package com.badbones69.crazycrates.paper.api;

import com.badbones69.crazycrates.paper.CrazyCrates;
import com.badbones69.crazycrates.paper.api.enums.other.keys.FileKeys;
import com.badbones69.crazycrates.paper.api.objects.Crate;
import com.badbones69.crazycrates.paper.api.objects.Prize;
import com.badbones69.crazycrates.paper.utils.ItemUtil;
import com.badbones69.crazycrates.paper.utils.MiscUtils;
import com.ryderbelserion.fusion.core.api.enums.Level;
import com.ryderbelserion.fusion.paper.FusionPaper;
import com.ryderbelserion.fusion.paper.builders.items.ItemBuilder;
import com.ryderbelserion.fusion.paper.builders.gui.types.paginated.PaginatedGui;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Server;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Pity system (AzurHOSTS fork), configured in pity.yml.
 * A pool groups one or more crates sharing a per-player pity ladder, an optional server-wide goal and a GUI bar.
 */
public final class PityManager {

    private static final CrazyCrates plugin = CrazyCrates.getPlugin();
    private static final CrazyCratesPaper platform = plugin.getPlatform();
    private static final FusionPaper fusion = platform.getFusion();
    private static final Server server = plugin.getServer();

    public record Step(int opens, @NotNull String crate, @NotNull String prize, @NotNull String style) {}

    public record Global(int goal, double percent, int minWinners, int minContribution, boolean onlineOnly,
                         @NotNull List<String> commands, @NotNull String broadcast, @NotNull String winnerMessage) {}

    public record Bar(@NotNull List<Integer> slots, boolean showPrevious, @NotNull String name, @NotNull List<String> lore, @Nullable ConfigurationSection styles) {}

    public record Pool(@NotNull String id, @NotNull List<String> crates, @NotNull List<Step> steps, @NotNull String message, @Nullable Global global, @Nullable Bar bar) {}

    public record Result(@NotNull Crate crate, @NotNull Prize prize) {}

    private static final Map<String, Pool> pools = new HashMap<>();
    private static final Map<String, Pool> byCrate = new HashMap<>();

    private static final List<String> fields = List.of(
            "global_remaining", "global_current", "global_percent", "global_goal", "contribution",
            "remaining", "previous", "current", "percent", "target", "steps", "prize", "step"
    );

    private PityManager() {}

    private static @NotNull String key(@NotNull final String crateName) {
        return crateName.replace(".yml", "").toLowerCase();
    }

    private static @Nullable Crate findCrate(@NotNull final String name) {
        final String key = key(name);

        for (final Crate crate : platform.getCrateManager().getCrates()) {
            if (key(crate.getFileName()).equals(key)) return crate;
        }

        return null;
    }

    /**
     * Loads pity.yml, must run after the crates are loaded.
     */
    public static void load() {
        pools.clear();
        byCrate.clear();

        final ConfigurationSection root = FileKeys.pity.getConfiguration().getConfigurationSection("pools");

        if (root == null) return;

        for (final String id : root.getKeys(false)) {
            final ConfigurationSection section = root.getConfigurationSection(id);

            if (section == null || !section.getBoolean("toggle", true)) continue;

            final List<String> crates = new ArrayList<>();

            for (final String name : section.getStringList("crates")) {
                if (findCrate(name) == null) {
                    fusion.log(Level.WARNING, "Pity pool %s: crate %s does not exist.", id, name);

                    continue;
                }

                crates.add(key(name));
            }

            if (crates.isEmpty()) {
                fusion.log(Level.WARNING, "Pity pool %s has no valid crate, skipping.", id);

                continue;
            }

            final List<Step> steps = new ArrayList<>();
            final ConfigurationSection stepsSection = section.getConfigurationSection("steps");

            if (stepsSection != null) {
                for (final String stepKey : stepsSection.getKeys(false)) {
                    final int opens = stepsSection.getInt(stepKey + ".opens", 0);
                    final String crateName = key(stepsSection.getString(stepKey + ".crate", crates.getFirst()));
                    final String prizeName = stepsSection.getString(stepKey + ".prize", "");
                    final Crate crate = findCrate(crateName);

                    if (opens < 1 || crate == null || crate.getPrize(prizeName) == null) {
                        fusion.log(Level.WARNING, "Pity pool %s: invalid step %s (opens must be > 0, crate and prize must exist).", id, stepKey);

                        continue;
                    }

                    steps.add(new Step(opens, crateName, prizeName, stepsSection.getString(stepKey + ".style", "default")));
                }
            }

            steps.sort(Comparator.comparingInt(Step::opens));

            Global global = null;
            final ConfigurationSection globalSection = section.getConfigurationSection("global");

            if (globalSection != null && globalSection.getBoolean("toggle", false)) {
                final int goal = globalSection.getInt("goal", 0);

                if (goal < 1) {
                    fusion.log(Level.WARNING, "Pity pool %s: global.goal must be > 0, global pity disabled.", id);
                } else {
                    global = new Global(
                            goal,
                            Math.clamp(globalSection.getDouble("winners-percent", 10), 0, 100),
                            Math.max(globalSection.getInt("min-winners", 1), 0),
                            Math.max(globalSection.getInt("min-contribution", 1), 1),
                            globalSection.getBoolean("online-only", false),
                            globalSection.getStringList("commands"),
                            globalSection.getString("broadcast", ""),
                            globalSection.getString("winner-message", "")
                    );
                }
            }

            Bar bar = null;
            final ConfigurationSection barSection = section.getConfigurationSection("bar");

            if (barSection != null && barSection.getBoolean("toggle", false) && !barSection.getIntegerList("slots").isEmpty()) {
                bar = new Bar(
                        barSection.getIntegerList("slots"),
                        barSection.getBoolean("show-previous", true),
                        barSection.getString("name", " "),
                        barSection.getStringList("lore"),
                        barSection.getConfigurationSection("styles")
                );
            }

            final Pool pool = new Pool(id, crates, steps, section.getString("message", ""), global, bar);

            pools.put(id.toLowerCase(), pool);

            for (final String crate : crates) {
                if (byCrate.putIfAbsent(crate, pool) != null) {
                    fusion.log(Level.WARNING, "Crate %s is in several pity pools, only %s is used.", crate, byCrate.get(crate).id());
                }
            }
        }
    }

    public static @Nullable Pool getPool(@NotNull final Crate crate) {
        return byCrate.get(key(crate.getFileName()));
    }

    /**
     * Counts one opening for the player and swaps the prize if a pity step is due.
     * A cycle restarts once the last step's prize is obtained, naturally or by pity.
     * A step is skipped when its prize (or a later step's prize) was obtained naturally during the cycle.
     */
    public static @NotNull Result apply(@NotNull final Player player, @NotNull final Crate crate, @NotNull final Prize prize) {
        final Pool pool = getPool(crate);

        if (pool == null) return new Result(crate, prize);

        final YamlConfiguration data = FileKeys.data.getConfiguration();

        final Result result = pool.steps().isEmpty() ? new Result(crate, prize) : applySteps(player, pool, crate, prize, data);

        final boolean completed = pool.global() != null && contribute(player.getUniqueId(), pool, data);

        FileKeys.data.save();

        if (completed) complete(pool);

        return result;
    }

    private static @NotNull Result applySteps(@NotNull final Player player, @NotNull final Pool pool, @NotNull final Crate crate, @NotNull final Prize prize, @NotNull final YamlConfiguration data) {
        final List<Step> steps = pool.steps();
        final String path = playerPath(player.getUniqueId(), pool);
        final String crateKey = key(crate.getFileName());

        final int count = data.getInt(path + ".count", 0) + 1;

        // steps[0 .. reached-1] are already obtained in this cycle.
        int reached = Math.clamp(data.getInt(path + ".step", 0), 0, steps.size());
        String last = data.getString(path + ".last", "");

        for (int index = steps.size(); index > reached; index--) {
            final Step step = steps.get(index - 1);

            if (step.crate().equals(crateKey) && step.prize().equalsIgnoreCase(prize.getSectionName())) {
                reached = index;
                last = step.crate() + ":" + step.prize();

                break;
            }
        }

        Result result = new Result(crate, prize);
        boolean forced = false;

        // Highest due step wins, so a step 2 "evolves" a pending step 1.
        for (int index = steps.size(); index > reached; index--) {
            final Step step = steps.get(index - 1);

            if (count < step.opens()) continue;

            final Crate pityCrate = findCrate(step.crate());
            final Prize pityPrize = pityCrate == null ? null : pityCrate.getPrize(step.prize());

            // Unavailable for now (blacklist, max pulls...): retried on the next opening.
            if (pityPrize == null || pityCrate.validatePrize(player, pityPrize)) continue;

            result = new Result(pityCrate, pityPrize);
            reached = index;
            last = step.crate() + ":" + step.prize();
            forced = true;

            break;
        }

        if (reached >= steps.size()) {
            data.set(path + ".count", 0);
            data.set(path + ".step", 0);
        } else {
            data.set(path + ".count", count);
            data.set(path + ".step", reached);
        }

        data.set(path + ".last", last.isEmpty() ? null : last);

        if (forced && !pool.message().isEmpty()) {
            final Map<String, String> placeholders = new HashMap<>();

            placeholders.put("%player%", player.getName());
            placeholders.put("%crate%", result.crate().getCrateName());
            placeholders.put("%reward%", result.prize().getPrizeName());
            placeholders.put("%opens%", String.valueOf(count));
            placeholders.put("%pool%", pool.id());

            player.sendMessage(fusion.asComponent(player, pool.message(), placeholders));
        }

        return result;
    }

    /**
     * @return true if the server-wide goal is reached.
     */
    private static boolean contribute(@NotNull final UUID uuid, @NotNull final Pool pool, @NotNull final YamlConfiguration data) {
        final String path = globalPath(pool);

        final int count = data.getInt(path + ".count", 0) + 1;

        data.set(path + ".count", count);
        data.set(path + ".contributors." + uuid, data.getInt(path + ".contributors." + uuid, 0) + 1);

        return pool.global() != null && count >= pool.global().goal();
    }

    private static void complete(@NotNull final Pool pool) {
        final Global global = pool.global();

        if (global == null) return;

        final YamlConfiguration data = FileKeys.data.getConfiguration();
        final String path = globalPath(pool);
        final ConfigurationSection contributors = data.getConfigurationSection(path + ".contributors");

        final List<UUID> candidates = new ArrayList<>();
        final Map<UUID, Integer> contributions = new HashMap<>();

        if (contributors != null) {
            for (final String key : contributors.getKeys(false)) {
                final UUID uuid;

                try {
                    uuid = UUID.fromString(key);
                } catch (final IllegalArgumentException exception) {
                    continue;
                }

                final int amount = contributors.getInt(key, 0);

                if (amount < global.minContribution()) continue;
                if (global.onlineOnly() && server.getPlayer(uuid) == null) continue;

                candidates.add(uuid);
                contributions.put(uuid, amount);
            }
        }

        // Reset first so a new cycle starts even if a reward command fails.
        data.set(path, null);
        FileKeys.data.save();

        Collections.shuffle(candidates);

        final int wanted = Math.max(global.minWinners(), (int) Math.ceil(candidates.size() * global.percent() / 100D));
        final List<UUID> winners = candidates.subList(0, Math.min(wanted, candidates.size()));

        final List<String> names = new ArrayList<>();

        for (final UUID uuid : winners) {
            final String name = server.getOfflinePlayer(uuid).getName();

            if (name == null) continue;

            names.add(name);

            final Map<String, String> placeholders = Map.of(
                    "%player%", name,
                    "%pool%", pool.id(),
                    "%contribution%", String.valueOf(contributions.get(uuid))
            );

            for (final String command : global.commands()) {
                MiscUtils.sendCommand(command, placeholders);
            }

            final Player player = server.getPlayer(uuid);

            if (player != null && !global.winnerMessage().isEmpty()) {
                player.sendMessage(fusion.asComponent(player, global.winnerMessage(), placeholders));
            }
        }

        if (!global.broadcast().isEmpty()) {
            server.broadcast(fusion.asComponent(Audience.empty(), global.broadcast(), Map.of(
                    "%pool%", pool.id(),
                    "%goal%", String.valueOf(global.goal()),
                    "%winners%", String.valueOf(names.size()),
                    "%winner_names%", names.isEmpty() ? "-" : String.join(", ", names),
                    "%contributors%", String.valueOf(candidates.size())
            )));
        }

        fusion.log(Level.INFO, "Global pity %s completed, winners: %s", pool.id(), String.join(", ", names));
    }

    private static @NotNull String playerPath(@NotNull final UUID uuid, @NotNull final Pool pool) {
        return "Players." + uuid + ".pity." + pool.id();
    }

    private static @NotNull String globalPath(@NotNull final Pool pool) {
        return "Pity-Global." + pool.id();
    }

    private static @Nullable Prize resolve(@NotNull final String reference) {
        final int split = reference.indexOf(':');

        if (split < 0) return null;

        final Crate crate = findCrate(reference.substring(0, split));

        return crate == null ? null : crate.getPrize(reference.substring(split + 1));
    }

    /**
     * Placeholders of a pool for a player, used by the bar and PlaceholderAPI (keys like %current%).
     */
    public static @NotNull Map<String, String> placeholders(@NotNull final UUID uuid, @NotNull final Pool pool) {
        final YamlConfiguration data = FileKeys.data.getConfiguration();
        final Map<String, String> placeholders = new HashMap<>();

        // Unused parts of the pool (no steps, no global) still render cleanly.
        for (final String field : fields) {
            placeholders.put("%" + field + "%", "-");
        }

        placeholders.put("%pool%", pool.id());

        final List<Step> steps = pool.steps();

        if (!steps.isEmpty()) {
            final String path = playerPath(uuid, pool);
            final int count = data.getInt(path + ".count", 0);
            final int reached = Math.clamp(data.getInt(path + ".step", 0), 0, steps.size() - 1);

            final Step next = steps.get(reached);
            final Prize nextPrize = resolve(next.crate() + ":" + next.prize());
            final Prize previous = resolve(data.getString(path + ".last", ""));

            placeholders.put("%current%", String.valueOf(count));
            placeholders.put("%target%", String.valueOf(next.opens()));
            placeholders.put("%remaining%", String.valueOf(Math.max(next.opens() - count, 0)));
            placeholders.put("%percent%", String.valueOf((int) (ratio(count, reached, steps) * 100)));
            placeholders.put("%step%", String.valueOf(reached + 1));
            placeholders.put("%steps%", String.valueOf(steps.size()));
            placeholders.put("%prize%", nextPrize == null ? "-" : nextPrize.getPrizeName());
            placeholders.put("%previous%", previous == null ? "-" : previous.getPrizeName());
        }

        final Global global = pool.global();

        if (global != null) {
            final String path = globalPath(pool);
            final int count = data.getInt(path + ".count", 0);

            placeholders.put("%global_current%", String.valueOf(count));
            placeholders.put("%global_goal%", String.valueOf(global.goal()));
            placeholders.put("%global_remaining%", String.valueOf(Math.max(global.goal() - count, 0)));
            placeholders.put("%global_percent%", String.valueOf(Math.min(count * 100L / global.goal(), 100)));
            placeholders.put("%contribution%", String.valueOf(data.getInt(path + ".contributors." + uuid, 0)));
        }

        return placeholders;
    }

    /**
     * Progress (0..1) from the previous step to the next one.
     */
    private static double ratio(final int count, final int reached, @NotNull final List<Step> steps) {
        final int from = reached > 0 ? steps.get(reached - 1).opens() : 0;
        final int to = steps.get(reached).opens();

        if (to <= from) return count >= to ? 1 : 0;

        return Math.clamp((count - from) / (double) (to - from), 0D, 1D);
    }

    /**
     * PlaceholderAPI: %crazycrates_pity_<field>_<pool or crate>%.
     */
    public static @NotNull Optional<String> placeholder(@NotNull final UUID uuid, @NotNull final String value) {
        for (final String field : fields) {
            if (!value.startsWith(field + "_")) continue;

            final String target = value.substring(field.length() + 1).toLowerCase();

            Pool pool = pools.get(target);

            if (pool == null) pool = byCrate.get(key(target));

            if (pool == null) return Optional.empty();

            return Optional.ofNullable(placeholders(uuid, pool).get("%" + field + "%"));
        }

        return Optional.empty();
    }

    /**
     * Draws the pity bar in a crate preview: the last slot shows the next pity prize,
     * the others are bar segments (empty/half/full), the first one can show the previous pity prize.
     */
    public static void addBar(@NotNull final Player player, @NotNull final Crate crate, @NotNull final PaginatedGui gui) {
        final Pool pool = getPool(crate);

        if (pool == null || pool.bar() == null || pool.steps().isEmpty()) return;

        final Bar bar = pool.bar();
        final List<Step> steps = pool.steps();
        final UUID uuid = player.getUniqueId();

        final YamlConfiguration data = FileKeys.data.getConfiguration();
        final String path = playerPath(uuid, pool);
        final int count = data.getInt(path + ".count", 0);
        final int reached = Math.clamp(data.getInt(path + ".step", 0), 0, steps.size() - 1);

        final Step next = steps.get(reached);
        final double ratio = ratio(count, reached, steps);
        final Map<String, String> placeholders = placeholders(uuid, pool);

        final List<Integer> slots = bar.slots();
        final int segments = slots.size() - 1;
        final int maxSlot = gui.getRows() * 9;

        final ConfigurationSection styles = bar.styles();
        ConfigurationSection style = styles == null ? null : styles.getConfigurationSection(next.style());

        if (style == null && styles != null) style = styles.getConfigurationSection("default");

        final Prize previous = bar.showPrevious() ? resolve(data.getString(path + ".last", "")) : null;

        for (int index = 0; index < segments; index++) {
            final int slot = slots.get(index);

            if (slot < 0 || slot >= maxSlot) continue;

            if (index == 0 && previous != null) {
                final Crate previousCrate = findCrate(data.getString(path + ".last", "").split(":")[0]);

                if (previousCrate != null) {
                    gui.addSlotAction(slot, previous.getDisplayItem(player, previousCrate), _ -> {});

                    continue;
                }
            }

            if (style == null) continue;

            final String state = ratio >= (index + 1D) / segments ? "full" : ratio >= (index + 0.5D) / segments ? "half" : "empty";
            final String position = index == 0 ? "start-" : index == segments - 1 ? "end-" : "";

            ConfigurationSection item = position.isEmpty() ? null : style.getConfigurationSection(position + state);

            if (item == null) item = style.getConfigurationSection(state);

            if (item == null) continue;

            gui.addSlotAction(slot, buildItem(player, item, bar, placeholders), _ -> {});
        }

        final int targetSlot = slots.getLast();
        final Crate nextCrate = findCrate(next.crate());
        final Prize nextPrize = nextCrate == null ? null : nextCrate.getPrize(next.prize());

        if (nextPrize != null && targetSlot >= 0 && targetSlot < maxSlot) {
            gui.addSlotAction(targetSlot, nextPrize.getDisplayItem(player, nextCrate), _ -> {});
        }
    }

    private static @NotNull ItemStack buildItem(@NotNull final Player player, @NotNull final ConfigurationSection section, @NotNull final Bar bar, @NotNull final Map<String, String> placeholders) {
        final ItemBuilder builder = ItemUtil.getItem(section, ItemBuilder.from(section.getString("Item", "gray_stained_glass_pane").toLowerCase()));

        final List<String> lore = new ArrayList<>();

        for (final String line : section.contains("Lore") ? section.getStringList("Lore") : bar.lore()) {
            lore.add(replace(line, placeholders));
        }

        return builder.withDisplayName(replace(section.getString("Name", bar.name()), placeholders)).withDisplayLore(lore).asItemStack(player);
    }

    private static @NotNull String replace(@NotNull String value, @NotNull final Map<String, String> placeholders) {
        for (final Map.Entry<String, String> entry : placeholders.entrySet()) {
            value = value.replace(entry.getKey(), entry.getValue());
        }

        return value;
    }
}
