package net.runelite.client.plugins.influxdb;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.influxdb.write.InfluxWriter;
import net.runelite.client.task.Schedule;

import javax.inject.Inject;
import java.time.temporal.ChronoUnit;

@PluginDescriptor(
        name = "InfluxDB Integration",
        description = "Saves data to influxdb",
        tags = {"experience", "levels", "stats"}
)
@Slf4j
public class InfluxDbPlugin extends Plugin {

    @Provides
    InfluxDbConfig getConfig(ConfigManager configManager) {
        return configManager.getConfig(InfluxDbConfig.class);
    }

    @Inject
    private InfluxWriter writer;

    @Inject
    private InfluxDbConfig config;

    @Inject
    private Client client;

    @Inject
    private MeasurementCreator measurer;

    @Subscribe
    public void onStatChanged(StatChanged statChanged) {
        if (!config.writeXp()) {
            return;
        }

        writer.submit(measurer.createXpMeasurement(statChanged.getSkill()));
        if (statChanged.getSkill() != Skill.OVERALL) {
            writer.submit(measurer.createXpMeasurement(Skill.OVERALL));
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGGED_IN && config.writeXp()) {
            for (Skill s : Skill.values()) {
                writer.submit(measurer.createXpMeasurement(s));
            }
        }
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        if (!config.writeBankValue()) return;
        ItemContainer container = event.getItemContainer();
        if (container == null)
            return;
        Item[] items = container.getItems();
        if (items == null)
            return;

        InventoryID id = null;
        for (InventoryID val : InventoryID.values()) {
            if (val.getId() == event.getContainerId()) {
                id = val;
                break;
            }
        }
        if (id != InventoryID.BANK && id != InventoryID.SEED_VAULT)
            return;
        if (writer.isBlocked(measurer.createItemSeries(id, MeasurementCreator.InvValueType.HA)))
            return;
        measurer.createItemMeasurements(id, items).forEach(writer::submit);
    }

    @Subscribe
    public void onGameTick(GameTick tick) {
        if (config.writeSelfLoc())
        writer.submit(measurer.createSelfLocMeasurement());
        if (config.writeSelfMeta())
            writer.submit(measurer.createSelfMeasurement());
    }

    @Schedule(period = 15, unit = ChronoUnit.SECONDS, asynchronous = true)
    public void flush() {
        writer.flush();
    }
}
