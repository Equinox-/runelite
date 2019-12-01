package net.runelite.client.plugins.influxdb;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Inject;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.Experience;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemID;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.VarPlayer;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.influxdb.write.Measurement;
import net.runelite.client.plugins.influxdb.write.Series;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MeasurementCreator {
    public static final String SERIES_INVENTORY = "rs_inventory";
    public static final String SERIES_SKILL = "rs_skill";
    public static final String SERIES_SELF = "rs_self";
    public static final String SERIES_SELF_LOC = "rs_self_loc";
    public static final String SELF_KEY_X = "locX";
    public static final String SELF_KEY_Y = "locY";
    public static final Set<String> SELF_POS_KEYS = ImmutableSet.of(SELF_KEY_X, SELF_KEY_Y);

    private final Client client;
    private final ItemManager itemManager;

    @Inject
    public MeasurementCreator(Client client, ItemManager itemManager) {
        this.client = client;
        this.itemManager = itemManager;
    }

    private Series.SeriesBuilder createSeries() {
        Series.SeriesBuilder builder = Series.builder();
        builder.tag("user", client.getUsername());
        // better logic to determine which unique instance of this player it is (ex deadman)
        return builder;
    }

    public Series createXpSeries(Skill skill) {
        return createSeries().measurement(SERIES_SKILL).tag("skill", skill.name()).build();
    }

    public Measurement createXpMeasurement(Skill skill) {
        long xp = skill == Skill.OVERALL ? client.getOverallExperience() : client.getSkillExperience(skill);
        int virtualLevel;
        int realLevel;
        if (skill == Skill.OVERALL) {
            virtualLevel = Arrays.stream(Skill.values())
                    .filter(x -> x != Skill.OVERALL)
                    .mapToInt(x -> Experience.getLevelForXp(client.getSkillExperience(x)))
                    .sum();
            realLevel = client.getTotalLevel();
        } else {
            virtualLevel = Experience.getLevelForXp((int) xp);
            realLevel = client.getRealSkillLevel(skill);
        }
        return Measurement.builder()
                .series(createXpSeries(skill))
                .numericValue("xp", xp)
                .numericValue("realLevel", realLevel)
                .numericValue("virtualLevel", virtualLevel)
                .build();
    }

    public Series createItemSeries(InventoryID inventory, InvValueType type) {
        return createSeries().measurement(SERIES_INVENTORY)
                .tag("inventory", inventory.name())
                .tag("type", type.name())
                .build();
    }

    public Stream<Measurement> createItemMeasurements(InventoryID inventoryID, Item[] items, int includeTopN) {
        Measurement.MeasurementBuilder geValue = Measurement.builder().series(createItemSeries(inventoryID, InvValueType.GE));
        Measurement.MeasurementBuilder haValue = Measurement.builder().series(createItemSeries(inventoryID, InvValueType.HA));
        Measurement.MeasurementBuilder quantityValue = Measurement.builder().series(createItemSeries(inventoryID, InvValueType.COUNT));

        Map<String, ItemValue> itemValues = new HashMap<>();
        for (Item item : items) {
            if (item.getId() < 0 || item.getQuantity() <= 0 || item.getId() == ItemID.BANK_FILLER)
                continue;
            int canonId = itemManager.canonicalize(item.getId());
            ItemComposition data = itemManager.getItemComposition(canonId);
            String key = data.getName() == null ? "Unknown" : data.getName();
            ItemValue value;
            switch (canonId) {
                case ItemID.COINS_995:
                    value = new ItemValue(key,
                            item.getQuantity(),
                            item.getQuantity(),
                            item.getQuantity());
                    break;
                case ItemID.PLATINUM_TOKEN:
                    value = new ItemValue(key,
                            item.getQuantity() * 1000L,
                            item.getQuantity() * 1000L,
                            item.getQuantity());
                    break;
                default:
                    final long storePrice = data.getPrice();
                    final long alchPrice = (long) (storePrice * Constants.HIGH_ALCHEMY_MULTIPLIER);
                    value = new ItemValue(key,
                            (long) itemManager.getItemPrice(canonId) * item.getQuantity(),
                            alchPrice * item.getQuantity(),
                            item.getQuantity());
                    break;
            }
            ItemValue existing = itemValues.get(key);
            if (existing != null) {
                existing.plus(value);
            } else {
                itemValues.put(key, value);
            }
        }

        List<ItemValue> values = itemValues.values().stream()
                .sorted(Comparator.<ItemValue>comparingLong(x -> Math.max(x.geValue, x.haValue)).reversed())
                .collect(Collectors.toList());
        ItemValue total = new ItemValue("total", 0, 0, 0);
        for (int i = 0; i < Math.min(values.size(), includeTopN); i++) {
            ItemValue val = values.get(i);
            total.plus(val);
            val.write(quantityValue, geValue, haValue);
        }
        ItemValue other = new ItemValue("other", 0, 0, 0);
        for (int i = includeTopN; i < values.size(); i++) {
            ItemValue val = values.get(i);
            total.plus(val);
            other.plus(val);
        }

        // Item count isn't useful for anything but specific items
        other.quantity = 0L;
        total.quantity = 0L;
        other.write(quantityValue, geValue, haValue);
        total.write(quantityValue, geValue, haValue);
        return Stream.of(geValue.build(), haValue.build(), quantityValue.build());
    }

    public Series createSelfLocSeries() {
        return createSeries().measurement(SERIES_SELF_LOC).build();
    }

    public Measurement createSelfLocMeasurement() {
        Player local = client.getLocalPlayer();
        WorldPoint location = WorldPoint.fromLocalInstance(client, local.getLocalLocation());
        return Measurement.builder()
                .series(createSelfLocSeries())
                .numericValue(SELF_KEY_X, location.getX())
                .numericValue(SELF_KEY_Y, location.getY())
                .numericValue("plane", location.getPlane())
                .numericValue("instance", client.isInInstancedRegion() ? 1 : 0)
                .build();
    }

    public Series createSelfSeries() {
        return createSeries().measurement(SERIES_SELF).build();
    }

    public Measurement createSelfMeasurement() {
        Player local = client.getLocalPlayer();
        return Measurement.builder()
                .series(createSelfSeries())
                .numericValue("combat", Experience.getCombatLevelPrecise(
                        client.getRealSkillLevel(Skill.ATTACK),
                        client.getRealSkillLevel(Skill.STRENGTH),
                        client.getRealSkillLevel(Skill.DEFENCE),
                        client.getRealSkillLevel(Skill.HITPOINTS),
                        client.getRealSkillLevel(Skill.MAGIC),
                        client.getRealSkillLevel(Skill.RANGED),
                        client.getRealSkillLevel(Skill.PRAYER)
                ))
                .numericValue("questPoints", client.getVar(VarPlayer.QUEST_POINTS))
                .numericValue("skulled", local.getSkullIcon() != null ? 1 : 0)
                .stringValue("name", MoreObjects.firstNonNull(local.getName(), "none"))
                .stringValue("overhead", local.getOverheadIcon() != null ? local.getOverheadIcon().name() : "NONE")
                .build();
    }

    enum InvValueType {
        GE,
        HA,
        COUNT
    }

    @AllArgsConstructor
    private static class ItemValue {
        String itemName;
        long geValue;
        long haValue;
        long quantity;

        void plus(ItemValue other) {
            geValue += other.geValue;
            haValue += other.haValue;
            quantity += other.quantity;
        }

        void write(Measurement.MeasurementBuilder quantityVal,
                   Measurement.MeasurementBuilder geVal,
                   Measurement.MeasurementBuilder haVal) {
            if (quantity > 0) {
                quantityVal.numericValue(itemName, quantity);
            }
            geVal.numericValue(itemName, geValue);
            haVal.numericValue(itemName, haValue);
        }
    }
}
