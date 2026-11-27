package org.openhab.binding.churchtoolsheating.internal;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.openhab.core.config.core.Configuration;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RoomHeaterHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(RoomHeaterHandler.class);
    
    private ScheduledFuture<?> pollingJob;
    private double currentTemp = 20.0;
    private double targetTemp = 20.0;
    private boolean summerMode = false;
    private boolean heatingEnabled = true;
    private boolean targetTempReceived = false;
    private boolean currentTempReceived = false;
    private boolean heatingEnabledReceived = false;
    private boolean summerModeReceived = false;
    private OnOffType relayState = null;
    private OnOffType targetRelayState = OnOffType.OFF;

    private ZonedDateTime manualOverrideUntil = null;
    private java.math.BigDecimal expectedHvacMode = null;
    private Double expectedActuatorTargetTemp = null;
    private OnOffType previousTargetRelayState = OnOffType.OFF;
    private ZonedDateTime latchedHeatingEventStart = null;
    private ZonedDateTime latchedVorlaufZeit = null;
    
    private List<ChurchToolsAPI.Booking> cachedBookings = null;
    private long lastApiFetchTime = 0;

    private volatile EventPublisher eventPublisher = null;
    private volatile ItemChannelLinkRegistry linkRegistry = null;
    private volatile ItemRegistry itemRegistry = null;

    public void setEventPublisher(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void setItemChannelLinkRegistry(ItemChannelLinkRegistry linkRegistry) {
        this.linkRegistry = linkRegistry;
    }

    public void setItemRegistry(ItemRegistry itemRegistry) {
        this.itemRegistry = itemRegistry;
    }

    private Double parseTemperatureState(State state) {
        if (state instanceof QuantityType) {
            return ((QuantityType<?>) state).toBigDecimal().doubleValue();
        } else if (state instanceof DecimalType) {
            return ((DecimalType) state).toBigDecimal().doubleValue();
        } else if (state instanceof StringType) {
            try {
                return Double.parseDouble(state.toString().replaceAll("[^0-9.-]", ""));
            } catch (NumberFormatException e) {}
        }
        return null;
    }

    private State getChannelItemState(String channelId) {
        ItemChannelLinkRegistry linkReg = this.linkRegistry;
        if (linkReg != null) {
            ChannelUID channelUID = new ChannelUID(getThing().getUID(), channelId);
            Set<Item> items = linkReg.getLinkedItems(channelUID);
            if (items != null) {
                for (Item item : items) {
                    State st = item.getState();
                    if (st != null && !(st instanceof UnDefType)) {
                        return st;
                    }
                }
            }
        }
        return null;
    }

    private State getConfiguredItemState(String configKey) {
        Object cfg = getConfig().get(configKey);
        if (cfg != null) {
            String itemName = cfg.toString().trim();
            if (!itemName.isEmpty()) {
                ItemRegistry itemReg = this.itemRegistry;
                if (itemReg != null) {
                    try {
                        Item item = itemReg.getItem(itemName);
                        State st = item.getState();
                        if (st != null && !(st instanceof UnDefType)) {
                            return st;
                        }
                    } catch (ItemNotFoundException e) {
                        // ignore
                    }
                }
            }
        }
        return null;
    }

    private void syncInputParametersFromItems() {
        // 1. Target Temperature (Sollwert Vorlauf)
        if (!targetTempReceived) {
            State targetState = getChannelItemState(ChurchToolsHeatingBindingConstants.CHANNEL_TARGET_TEMPERATURE);
            Double parsedTarget = parseTemperatureState(targetState);
            if (parsedTarget != null && parsedTarget > 0) {
                this.targetTemp = parsedTarget;
            }
        }

        // 2. Current Temperature (Ist-Temperatur via knxCurrentTempItem or Channel)
        if (!currentTempReceived) {
            State currentState = getConfiguredItemState("knxCurrentTempItem");
            if (currentState == null) {
                currentState = getChannelItemState(ChurchToolsHeatingBindingConstants.CHANNEL_CURRENT_TEMPERATURE);
            }
            Double parsedCurrent = parseTemperatureState(currentState);
            if (parsedCurrent != null && parsedCurrent > 0) {
                this.currentTemp = parsedCurrent;
            }
        }

        // 3. Heating Enabled
        if (!heatingEnabledReceived) {
            State enabledState = getChannelItemState(ChurchToolsHeatingBindingConstants.CHANNEL_HEATING_ENABLED);
            if (enabledState instanceof OnOffType) {
                this.heatingEnabled = (enabledState == OnOffType.ON);
            }
        }

        // 4. Summer Mode
        if (!summerModeReceived) {
            State summerState = getChannelItemState(ChurchToolsHeatingBindingConstants.CHANNEL_SUMMER_MODE);
            if (summerState instanceof OnOffType) {
                this.summerMode = (summerState == OnOffType.ON);
            }
        }
    }

    private void sendCommandToConfiguredItem(String configKey, String channelId, Command command) {
        Object itemCfg = getConfig().get(configKey);
        if (itemCfg != null) {
            String itemName = itemCfg.toString().trim();
            if (!itemName.isEmpty()) {
                ItemChannelLinkRegistry registry = this.linkRegistry;
                if (registry != null) {
                    Set<ChannelUID> boundChannels = registry.getBoundChannels(itemName);
                    if (boundChannels != null) {
                        ChannelUID thisChannelUID = new ChannelUID(getThing().getUID(), channelId);
                        if (boundChannels.contains(thisChannelUID)) {
                            logger.debug("Item {} is already bound to channel {}, skipping direct EventPublisher command.", itemName, channelId);
                            return;
                        }
                    }
                }

                EventPublisher publisher = this.eventPublisher;
                if (publisher != null) {
                    try {
                        publisher.post(ItemEventFactory.createCommandEvent(itemName, command, "org.openhab.binding.churchtoolsheating"));
                        logger.info("Sent direct command {} to configured item {}", command, itemName);
                    } catch (Exception e) {
                        logger.warn("Failed to send direct command to item {}: {}", itemName, e.getMessage());
                    }
                }
            }
        }
    }

    public RoomHeaterHandler(Thing thing) {
        super(thing);
    }

    int getIntConfig(String key, int defaultVal) {
        Object val = getConfig().get(key);
        if (val == null) return defaultVal;
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) {
            try { return Integer.parseInt((String) val); } catch (NumberFormatException e) {}
        }
        return defaultVal;
    }

    boolean getBooleanConfig(String key, boolean defaultVal) {
        Object val = getConfig().get(key);
        if (val == null) return defaultVal;
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof String) return Boolean.parseBoolean((String) val);
        return defaultVal;
    }

    double getDoubleConfig(String key, double defaultVal) {
        Object val = getConfig().get(key);
        if (val == null) return defaultVal;
        if (val instanceof Number) return ((Number) val).doubleValue();
        if (val instanceof String) {
            try { return Double.parseDouble((String) val); } catch (NumberFormatException e) {}
        }
        return defaultVal;
    }

    @Override
    public void initialize() {
        logger.debug("Initializing Room Heater Handler for {}", getThing().getUID());
        updateStatus(ThingStatus.ONLINE);
        
        // Start polling logic every 1 minute for fast reaction to event boundaries
        pollingJob = scheduler.scheduleWithFixedDelay(this::evaluateHeatingLogic, 0, 1, TimeUnit.MINUTES);
    }

    @Override
    public void dispose() {
        if (pollingJob != null) {
            pollingJob.cancel(true);
        }
        super.dispose();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof State) {
            processChannelState(channelUID.getId(), (State) command, true);
        }
    }

    public void onItemNameStateChanged(String itemName, State state) {
        if (itemName == null) return;
        Object hvacCfg = getConfig().get("knxHvacModeItem");
        Object targetCfg = getConfig().get("knxTargetTempItem");
        Object currentCfg = getConfig().get("knxCurrentTempItem");

        if (hvacCfg != null && itemName.equalsIgnoreCase(hvacCfg.toString().trim())) {
            logger.info("RoomHeater [{}] matched knxHvacModeItem {}: state={}", getThing().getUID().getId(), itemName, state);
            processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_HVAC_MODE, state, false);
        } else if (targetCfg != null && itemName.equalsIgnoreCase(targetCfg.toString().trim())) {
            logger.info("RoomHeater [{}] matched knxTargetTempItem {}: state={}", getThing().getUID().getId(), itemName, state);
            processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_ACTUATOR_TARGET_TEMP, state, false);
        } else if (currentCfg != null && itemName.equalsIgnoreCase(currentCfg.toString().trim())) {
            logger.info("RoomHeater [{}] matched knxCurrentTempItem {}: state={}", getThing().getUID().getId(), itemName, state);
            processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_CURRENT_TEMPERATURE, state, false);
        }
    }

    public void processChannelState(String channelId, State state) {
        processChannelState(channelId, state, false);
    }

    public synchronized void processChannelState(String channelId, State state, boolean isExplicitCommand) {
        logger.debug("RoomHeater [{}] processChannelState: channelId={}, state={}, isExplicit={}",
                getThing().getUID().getId(), channelId, state, isExplicitCommand);
        switch (channelId) {
                case ChurchToolsHeatingBindingConstants.CHANNEL_CURRENT_TEMPERATURE:
                    double newCurrentTemp = -1;
                    if (state instanceof QuantityType) {
                        newCurrentTemp = ((QuantityType<?>) state).toBigDecimal().doubleValue();
                    } else if (state instanceof DecimalType) {
                        newCurrentTemp = ((DecimalType) state).toBigDecimal().doubleValue();
                    } else if (state instanceof StringType) {
                        try {
                            newCurrentTemp = Double.parseDouble(state.toString().replaceAll("[^0-9.-]", ""));
                        } catch (NumberFormatException e) {}
                    }
                    if (newCurrentTemp > 0) {
                        currentTempReceived = true;
                        if (Math.abs(currentTemp - newCurrentTemp) >= 0.05) {
                            currentTemp = newCurrentTemp;
                            evaluateHeatingLogic();
                        }
                    }
                    break;
                case ChurchToolsHeatingBindingConstants.CHANNEL_TARGET_TEMPERATURE:
                    double newTargetTemp = -1;
                    if (state instanceof QuantityType) {
                        newTargetTemp = ((QuantityType<?>) state).toBigDecimal().doubleValue();
                    } else if (state instanceof DecimalType) {
                        newTargetTemp = ((DecimalType) state).toBigDecimal().doubleValue();
                    } else if (state instanceof StringType) {
                        try {
                            newTargetTemp = Double.parseDouble(state.toString().replaceAll("[^0-9.-]", ""));
                        } catch (NumberFormatException e) {}
                    }
                    if (newTargetTemp > 0) {
                        targetTempReceived = true;
                        if (Math.abs(targetTemp - newTargetTemp) >= 0.05) {
                            targetTemp = newTargetTemp;
                            evaluateHeatingLogic();
                        }
                    }
                    break;
                case ChurchToolsHeatingBindingConstants.CHANNEL_SUMMER_MODE:
                    boolean newSummer = summerMode;
                    if (state instanceof OnOffType) {
                        newSummer = state == OnOffType.ON;
                    } else if (state instanceof StringType) {
                        newSummer = state.toString().equalsIgnoreCase("ON");
                    }
                    summerModeReceived = true;
                    if (newSummer != summerMode) {
                        boolean wasHeatingByAutomation = !summerMode && newSummer && heatingEnabled
                                && (targetRelayState == OnOffType.ON || relayState == OnOffType.ON)
                                && manualOverrideUntil == null;
                        summerMode = newSummer;
                        if (summerMode) {
                            manualOverrideUntil = null;
                            previousTargetRelayState = OnOffType.OFF;
                            updateState(ChurchToolsHeatingBindingConstants.CHANNEL_MANUAL_OVERRIDE_UNTIL, UnDefType.UNDEF);
                            updateRelay(OnOffType.OFF);
                            if (wasHeatingByAutomation) {
                                int configuredOffMode = getIntConfig("offHvacMode", 3);
                                double offTemp = getDoubleConfig("offTemperature", 17.0);
                                updateHvacMode(java.math.BigDecimal.valueOf(configuredOffMode));
                                updateActuatorTargetTemp(offTemp);
                                logger.info("Summer mode enabled while actively heating by schedule for resource {}. Turning KNX heating OFF once.",
                                        getConfig().get("resourceId"));
                            }
                        }
                        updateState(ChurchToolsHeatingBindingConstants.CHANNEL_SUMMER_MODE, summerMode ? OnOffType.ON : OnOffType.OFF);
                        evaluateHeatingLogic();
                    }
                    break;
                case ChurchToolsHeatingBindingConstants.CHANNEL_HEATING_ENABLED:
                    boolean newEnabled = heatingEnabled;
                    if (state instanceof OnOffType || state instanceof StringType) {
                        newEnabled = state.toString().equalsIgnoreCase("ON");
                    }
                    heatingEnabledReceived = true;
                    if (newEnabled != heatingEnabled) {
                        boolean wasHeatingByAutomation = heatingEnabled && !newEnabled && !summerMode
                                && (targetRelayState == OnOffType.ON || relayState == OnOffType.ON)
                                && manualOverrideUntil == null;
                        heatingEnabled = newEnabled;
                        manualOverrideUntil = null;
                        previousTargetRelayState = OnOffType.OFF;
                        updateState(ChurchToolsHeatingBindingConstants.CHANNEL_MANUAL_OVERRIDE_UNTIL, UnDefType.UNDEF);
                        updateState(ChurchToolsHeatingBindingConstants.CHANNEL_HEATING_ENABLED, heatingEnabled ? OnOffType.ON : OnOffType.OFF);
                        if (!heatingEnabled) {
                            updateRelay(OnOffType.OFF);
                            if (wasHeatingByAutomation) {
                                int configuredOffMode = getIntConfig("offHvacMode", 3);
                                double offTemp = getDoubleConfig("offTemperature", 17.0);
                                updateHvacMode(java.math.BigDecimal.valueOf(configuredOffMode));
                                updateActuatorTargetTemp(offTemp);
                                logger.info("Heating automation disabled while actively heating by schedule for resource {}. Turning KNX heating OFF once.",
                                        getConfig().get("resourceId"));
                            } else {
                                logger.info("Heating automation disabled while in manual mode or idle for resource {}. Leaving KNX bus untouched.",
                                        getConfig().get("resourceId"));
                            }
                        }
                        evaluateHeatingLogic();
                    }
                    break;
                case ChurchToolsHeatingBindingConstants.CHANNEL_MANUAL_OVERRIDE_UNTIL:
                    if (isExplicitCommand && (state instanceof UnDefType || (state instanceof StringType && state.toString().equals("UNDEF")))) {
                        if (manualOverrideUntil != null) {
                            manualOverrideUntil = null;
                            updateState(ChurchToolsHeatingBindingConstants.CHANNEL_MANUAL_OVERRIDE_UNTIL, UnDefType.UNDEF);
                            logger.info("Manual override was explicitly reset by user.");
                            evaluateHeatingLogic();
                        }
                    }
                    break;
                case ChurchToolsHeatingBindingConstants.CHANNEL_ACTUATOR_TARGET_TEMP:
                    double newActuatorTemp = -1;
                    if (state instanceof QuantityType) {
                        newActuatorTemp = ((QuantityType<?>) state).toBigDecimal().doubleValue();
                    } else if (state instanceof DecimalType) {
                        newActuatorTemp = ((DecimalType) state).toBigDecimal().doubleValue();
                    } else if (state instanceof StringType) {
                        try {
                            newActuatorTemp = Double.parseDouble(state.toString().replaceAll("[^0-9.-]", ""));
                        } catch (NumberFormatException e) {}
                    }
                    if (newActuatorTemp > 0) {
                        if (!heatingEnabled || summerMode) {
                            expectedActuatorTargetTemp = newActuatorTemp;
                            break;
                        }
                        boolean enableExternalManualMode = getBooleanConfig("enableExternalManualMode", true);
                        double offTemp = getDoubleConfig("offTemperature", 17.0);
                        double defaultExpected = (targetRelayState == OnOffType.ON) ? targetTemp : offTemp;
                        double currentExpected = expectedActuatorTargetTemp != null ? expectedActuatorTargetTemp : defaultExpected;
                        expectedActuatorTargetTemp = newActuatorTemp;
                        
                        if (isExplicitCommand || enableExternalManualMode) {
                            if (targetRelayState == OnOffType.OFF && manualOverrideUntil != null && newActuatorTemp <= offTemp && currentExpected > (offTemp + 1.0)) {
                                // Temperature turned back down to or below offTemp during manual override outside event: clear manual override without changing HVAC mode
                                manualOverrideUntil = null;
                                updateState(ChurchToolsHeatingBindingConstants.CHANNEL_MANUAL_OVERRIDE_UNTIL, UnDefType.UNDEF);
                                updateRelay(OnOffType.OFF);
                                logger.info("External Setpoint turned down to offTemp for resource {} ({} °C <= offTemp {} °C). Manual override cleared (HVAC mode unchanged).",
                                        getConfig().get("resourceId"), newActuatorTemp, offTemp);
                            } else if (Math.abs(newActuatorTemp - currentExpected) > 1.0) {
                                // Setpoint changed by more than 1.0 °C: activate manual override WITHOUT changing HVAC mode (Betriebsart)
                                int overrideHours = getIntConfig("externalManualModeDuration", 6);
                                manualOverrideUntil = ZonedDateTime.now().plusHours(overrideHours);
                                updateState(ChurchToolsHeatingBindingConstants.CHANNEL_MANUAL_OVERRIDE_UNTIL, new DateTimeType(manualOverrideUntil));
                                updateRelay(newActuatorTemp > (offTemp + 1.0) ? OnOffType.ON : OnOffType.OFF);
                                logger.info("Setpoint override (> 1.0 °C) detected for resource {}. Temp changed from {} to {}. Suspending automation for {} hours until {} (HVAC mode unchanged).",
                                        getConfig().get("resourceId"), currentExpected, newActuatorTemp, overrideHours, manualOverrideUntil);
                            }
                        }
                    }
                    break;
                case ChurchToolsHeatingBindingConstants.CHANNEL_HVAC_MODE:
                    java.math.BigDecimal newMode = null;
                    if (state instanceof DecimalType) {
                        newMode = ((DecimalType) state).toBigDecimal();
                    } else if (state instanceof StringType) {
                        try {
                            newMode = new java.math.BigDecimal(state.toString());
                        } catch (NumberFormatException e) {}
                    }
                    
                    if (newMode != null) {
                        if (!heatingEnabled || summerMode) {
                            boolean modeChanged = (expectedHvacMode == null || newMode.compareTo(expectedHvacMode) != 0);
                            expectedHvacMode = newMode;
                            if (modeChanged) {
                                updateState(ChurchToolsHeatingBindingConstants.CHANNEL_HVAC_MODE, new DecimalType(newMode));
                                if (isExplicitCommand) {
                                    sendCommandToConfiguredItem("knxHvacModeItem", ChurchToolsHeatingBindingConstants.CHANNEL_HVAC_MODE, new DecimalType(newMode));
                                }
                            }
                            break;
                        }
                        boolean enableExternalManualMode = getBooleanConfig("enableExternalManualMode", true);
                        java.math.BigDecimal currentExpectedMode = expectedHvacMode != null ? expectedHvacMode : java.math.BigDecimal.valueOf(getIntConfig("offHvacMode", 3));
                        boolean modeChanged = (expectedHvacMode == null || newMode.compareTo(expectedHvacMode) != 0);
                        expectedHvacMode = newMode;

                        if ((isExplicitCommand || enableExternalManualMode) && newMode.compareTo(currentExpectedMode) != 0) {
                            // If the automation is currently in a scheduled heating phase (targetRelayState == ON) and mode is 1 (Komfort),
                            // and NO manual override was active, this is the automation doing its job, NOT a manual override!
                            if (!isExplicitCommand && manualOverrideUntil == null && targetRelayState == OnOffType.ON && newMode.intValue() == 1) {
                                logger.debug("HVAC Mode 1 matches active event heating phase for resource {}. Skipping manual override.", getConfig().get("resourceId"));
                            } else {
                                if (newMode.intValue() == 1 || newMode.intValue() == 2) {
                                    syncInputParametersFromItems();
                                    if (targetRelayState == OnOffType.ON) {
                                        // Resuming scheduled event heating: clear manual OFF override
                                        manualOverrideUntil = null;
                                        updateState(ChurchToolsHeatingBindingConstants.CHANNEL_MANUAL_OVERRIDE_UNTIL, UnDefType.UNDEF);
                                        updateRelay(OnOffType.ON);
                                        updateActuatorTargetTemp(targetTemp);
                                        logger.info("Resuming scheduled event heating for resource {} (explicit={}).", getConfig().get("resourceId"), isExplicitCommand);
                                    } else {
                                        int overrideHours = getIntConfig("externalManualModeDuration", 6);
                                        manualOverrideUntil = ZonedDateTime.now().plusHours(overrideHours);
                                        updateState(ChurchToolsHeatingBindingConstants.CHANNEL_MANUAL_OVERRIDE_UNTIL, new DateTimeType(manualOverrideUntil));
                                        updateRelay(OnOffType.ON);
                                        updateActuatorTargetTemp(targetTemp);
                                        logger.info("HVAC ON override detected for resource {} (explicit={}). Suspending automation for {} hours until {}",
                                                getConfig().get("resourceId"), isExplicitCommand, overrideHours, manualOverrideUntil);
                                    }
                                } else if (newMode.intValue() == 3 || newMode.intValue() == 4) {
                                    if (targetRelayState == OnOffType.ON) {
                                        // Active scheduled event heating is currently running!
                                        // Turning to Nacht/Frost is a manual OFF override: suspend automation so it doesn't force heat back on.
                                        int overrideHours = getIntConfig("externalManualModeDuration", 6);
                                        manualOverrideUntil = ZonedDateTime.now().plusHours(overrideHours);
                                        updateState(ChurchToolsHeatingBindingConstants.CHANNEL_MANUAL_OVERRIDE_UNTIL, new DateTimeType(manualOverrideUntil));
                                        updateRelay(OnOffType.OFF);
                                        updateActuatorTargetTemp(getDoubleConfig("offTemperature", 17.0));
                                        logger.info("HVAC OFF override during active heating detected for resource {} (explicit={}). Suspending heating for {} hours until {}",
                                                getConfig().get("resourceId"), isExplicitCommand, overrideHours, manualOverrideUntil);
                                    } else {
                                        // Normal idle state: clear manual override
                                        manualOverrideUntil = null;
                                        updateState(ChurchToolsHeatingBindingConstants.CHANNEL_MANUAL_OVERRIDE_UNTIL, UnDefType.UNDEF);
                                        updateRelay(OnOffType.OFF);
                                        updateActuatorTargetTemp(getDoubleConfig("offTemperature", 17.0));
                                        logger.info("HVAC OFF override detected for resource {} (explicit={}). Manual override cleared, heating turned OFF.",
                                                getConfig().get("resourceId"), isExplicitCommand);
                                    }
                                }
                            }
                        }
                        if (modeChanged) {
                            updateState(ChurchToolsHeatingBindingConstants.CHANNEL_HVAC_MODE, new DecimalType(newMode));
                            evaluateHeatingLogic();
                        }
                    }
                    break;
        }
    }

    private void evaluateHeatingLogic() {
        try {
            if (getBridge() == null || getBridge().getStatus() != ThingStatus.ONLINE) {
                return;
            }
            if (getBridge().getHandler() == null) {
                return;
            }

            syncInputParametersFromItems();

            ChurchToolsBridgeHandler bridgeHandler = (ChurchToolsBridgeHandler) getBridge().getHandler();
            ChurchToolsAPI api = bridgeHandler.getApi();
            if (api == null) {
                return;
            }
            
            int resourceId = getIntConfig("resourceId", 0);
            boolean onlyConfirmed = getBooleanConfig("onlyConfirmedBookings", true);

            int refreshInterval = bridgeHandler.getRefreshInterval();
            long cacheDurationMs = refreshInterval * 60 * 1000L;

            List<ChurchToolsAPI.Booking> bookings = cachedBookings;
            long nowMs = System.currentTimeMillis();
            if (bookings == null || nowMs - lastApiFetchTime >= cacheDurationMs) {
                bookings = api.getBookings(resourceId, onlyConfirmed);
                cachedBookings = bookings;
                lastApiFetchTime = nowMs;
            }
            
            // Sort bookings by start date
            bookings.sort((b1, b2) -> b1.startDate.compareTo(b2.startDate));

            // Read configuration for max duration filtering
            boolean enableFilter = getBooleanConfig("enableMaxDurationFilter", true);
            int maxDuration = getIntConfig("maxEventDuration", 10);

            ZonedDateTime now = ZonedDateTime.now();
            List<ChurchToolsAPI.Booking> upcoming = new java.util.ArrayList<>();
            for (ChurchToolsAPI.Booking booking : bookings) {
                if (booking.endDate.isAfter(now)) {
                    long durationH = Duration.between(booking.startDate, booking.endDate).toHours();
                    if (!enableFilter || durationH <= maxDuration) {
                        upcoming.add(booking);
                    }
                }
            }

            // Build string for next 3 events
            String event1 = upcoming.size() > 0 ? upcoming.get(0).caption : "Keine Termine";
            String event2 = upcoming.size() > 1 ? upcoming.get(1).caption : "-";
            String event3 = upcoming.size() > 2 ? upcoming.get(2).caption : "-";

            updateState(ChurchToolsHeatingBindingConstants.CHANNEL_UPCOMING_EVENT_1, new StringType(event1));
            updateState(ChurchToolsHeatingBindingConstants.CHANNEL_UPCOMING_EVENT_2, new StringType(event2));
            updateState(ChurchToolsHeatingBindingConstants.CHANNEL_UPCOMING_EVENT_3, new StringType(event3));

            if (upcoming.size() > 1) {
                updateState(ChurchToolsHeatingBindingConstants.CHANNEL_NEXT_EVENT_2_START, new DateTimeType(upcoming.get(1).startDate));
                updateState(ChurchToolsHeatingBindingConstants.CHANNEL_NEXT_EVENT_2_END, new DateTimeType(upcoming.get(1).endDate));
            } else {
                updateState(ChurchToolsHeatingBindingConstants.CHANNEL_NEXT_EVENT_2_START, UnDefType.UNDEF);
                updateState(ChurchToolsHeatingBindingConstants.CHANNEL_NEXT_EVENT_2_END, UnDefType.UNDEF);
            }

            if (upcoming.size() > 2) {
                updateState(ChurchToolsHeatingBindingConstants.CHANNEL_NEXT_EVENT_3_START, new DateTimeType(upcoming.get(2).startDate));
                updateState(ChurchToolsHeatingBindingConstants.CHANNEL_NEXT_EVENT_3_END, new DateTimeType(upcoming.get(2).endDate));
            } else {
                updateState(ChurchToolsHeatingBindingConstants.CHANNEL_NEXT_EVENT_3_START, UnDefType.UNDEF);
                updateState(ChurchToolsHeatingBindingConstants.CHANNEL_NEXT_EVENT_3_END, UnDefType.UNDEF);
            }

            // 1. Update display channels for primary upcoming event
            if (upcoming.size() > 0) {
                updateState(ChurchToolsHeatingBindingConstants.CHANNEL_NEXT_EVENT_START, new DateTimeType(upcoming.get(0).startDate));
                updateState(ChurchToolsHeatingBindingConstants.CHANNEL_NEXT_EVENT_END, new DateTimeType(upcoming.get(0).endDate));
            } else {
                updateState(ChurchToolsHeatingBindingConstants.CHANNEL_NEXT_EVENT_START, UnDefType.UNDEF);
                updateState(ChurchToolsHeatingBindingConstants.CHANNEL_NEXT_EVENT_END, UnDefType.UNDEF);
            }

            // Find valid event for heating (taking earlyStopMinutes into account)
            int earlyStopMinutes = getIntConfig("earlyStopMinutes", 0);
            if (earlyStopMinutes < 0) {
                earlyStopMinutes = 0;
            }

            ZonedDateTime eventStart = null;
            ZonedDateTime heatingEnd = null;
            for (ChurchToolsAPI.Booking b : upcoming) {
                ZonedDateTime effectiveEnd = b.endDate.minusMinutes(earlyStopMinutes);
                if (effectiveEnd.isAfter(now)) {
                    eventStart = b.startDate;
                    heatingEnd = effectiveEnd;
                    break;
                }
            }

            if (eventStart == null) {
                latchedHeatingEventStart = null;
                latchedVorlaufZeit = null;
                updateState(ChurchToolsHeatingBindingConstants.CHANNEL_HEATING_START, UnDefType.UNDEF);
                targetRelayState = OnOffType.OFF;
            } else {
                if (latchedHeatingEventStart != null && !latchedHeatingEventStart.isEqual(eventStart)) {
                    latchedHeatingEventStart = null;
                    latchedVorlaufZeit = null;
                }

                // 2. Calculate Pre-heat time (4-point curve)
                boolean enablePreheating = getBooleanConfig("enablePreheating", true);

                double vorlaufH = 0.0;

                if (enablePreheating) {
                    double deltaT = targetTemp - currentTemp;
                    if (deltaT < 0) deltaT = 0;

                    double dt1 = getDoubleConfig("deltaT1", 0.5);
                    double dt2 = getDoubleConfig("deltaT2", 1.0);
                    double dt3 = getDoubleConfig("deltaT3", 2.0);
                    double dt4 = getDoubleConfig("deltaT4", 4.0);

                    double h1 = getDoubleConfig("vorlauf1", 1.0);
                    double h2 = getDoubleConfig("vorlauf2", 2.5);
                    double h3 = getDoubleConfig("vorlauf3", 5.0);
                    double h4 = getDoubleConfig("vorlauf4", 8.0);

                    if (deltaT <= dt1) {
                        vorlaufH = h1;
                    } else if (deltaT <= dt2) {
                        vorlaufH = (dt2 > dt1) ? h1 + (h2 - h1) * ((deltaT - dt1) / (dt2 - dt1)) : h2;
                    } else if (deltaT <= dt3) {
                        vorlaufH = (dt3 > dt2) ? h2 + (h3 - h2) * ((deltaT - dt2) / (dt3 - dt2)) : h3;
                    } else if (deltaT <= dt4) {
                        vorlaufH = (dt4 > dt3) ? h3 + (h4 - h3) * ((deltaT - dt3) / (dt4 - dt3)) : h4;
                    } else {
                        vorlaufH = h4;
                    }
                } else {
                    latchedHeatingEventStart = null;
                    latchedVorlaufZeit = null;
                }

                int vorlaufInMinuten = (int) (vorlaufH * 60.0);
                ZonedDateTime vorlaufZeit = eventStart.minusMinutes(vorlaufInMinuten);

                // Latch preheating start once active for this event so rising room temperature does not pause preheating
                if (latchedHeatingEventStart != null && latchedVorlaufZeit != null && now.isBefore(heatingEnd)) {
                    if (vorlaufZeit.isAfter(latchedVorlaufZeit)) {
                        vorlaufZeit = latchedVorlaufZeit;
                    } else {
                        latchedVorlaufZeit = vorlaufZeit;
                    }
                }

                updateState(ChurchToolsHeatingBindingConstants.CHANNEL_HEATING_START, new DateTimeType(vorlaufZeit));

                logger.debug("Heating evaluation: targetTemp={}, currentTemp={}, deltaT={}, vorlaufH={}h ({} min), eventStart={}, heatingEnd={} (earlyStop={} min), vorlaufZeit={}",
                        targetTemp, currentTemp, (targetTemp - currentTemp), vorlaufH, vorlaufInMinuten, eventStart, heatingEnd, earlyStopMinutes, vorlaufZeit);

                // 3. Control logic
                if (summerMode || !heatingEnabled) {
                    latchedHeatingEventStart = null;
                    latchedVorlaufZeit = null;
                    targetRelayState = OnOffType.OFF;
                } else if (now.isAfter(vorlaufZeit) && now.isBefore(heatingEnd)) {
                    targetRelayState = OnOffType.ON;
                    latchedHeatingEventStart = eventStart;
                    if (latchedVorlaufZeit == null || vorlaufZeit.isBefore(latchedVorlaufZeit)) {
                        latchedVorlaufZeit = vorlaufZeit;
                    }
                } else {
                    targetRelayState = OnOffType.OFF;
                }
            }

            // If a new heating phase just started, automatically reset any manual override
            if (targetRelayState == OnOffType.ON && previousTargetRelayState == OnOffType.OFF) {
                if (manualOverrideUntil != null) {
                    manualOverrideUntil = null;
                    updateState(ChurchToolsHeatingBindingConstants.CHANNEL_MANUAL_OVERRIDE_UNTIL, UnDefType.UNDEF);
                    logger.info("New event heating phase started. Automatically resetting manual override for resource {}.", getConfig().get("resourceId"));
                }
            }
            previousTargetRelayState = targetRelayState;

            // Determine target HVAC mode
            java.math.BigDecimal targetHvacMode = java.math.BigDecimal.valueOf(1); // 1 = Komfort
            double actuatorTargetTempVal = targetTemp;

            if (targetRelayState == OnOffType.OFF) {
                actuatorTargetTempVal = getDoubleConfig("offTemperature", 17.0);
                int configuredOffMode = getIntConfig("offHvacMode", 3);
                if (expectedHvacMode != null && (expectedHvacMode.intValue() == 3 || expectedHvacMode.intValue() == 4)) {
                    targetHvacMode = expectedHvacMode;
                } else {
                    targetHvacMode = java.math.BigDecimal.valueOf(configuredOffMode);
                }
            }

            if (!heatingEnabled || summerMode) {
                // Automation is disabled: ensure CT heatingRelay & actualHeatingStart banner are OFF,
                // and do not interfere with the KNX bus on periodic polling (hands off).
                updateRelay(OnOffType.OFF);
                updateState(ChurchToolsHeatingBindingConstants.CHANNEL_HEATING_ENABLED, heatingEnabled ? OnOffType.ON : OnOffType.OFF);
                updateState(ChurchToolsHeatingBindingConstants.CHANNEL_SUMMER_MODE, summerMode ? OnOffType.ON : OnOffType.OFF);
                return;
            }

            // Check manual override
            if (manualOverrideUntil != null) {
                if (now.isBefore(manualOverrideUntil)) {
                    // Override active, do not change relay or hvac mode
                    logger.debug("Manual override active until {}. Skipping control logic.", manualOverrideUntil);
                } else {
                    // Override expired
                    manualOverrideUntil = null;
                    updateState(ChurchToolsHeatingBindingConstants.CHANNEL_MANUAL_OVERRIDE_UNTIL, UnDefType.UNDEF);
                    logger.info("Manual override expired. Resuming automation.");
                    updateRelay(targetRelayState);
                    updateHvacMode(targetHvacMode);
                    updateActuatorTargetTemp(actuatorTargetTempVal);
                }
            } else {
                updateRelay(targetRelayState);
                updateHvacMode(targetHvacMode);
                updateActuatorTargetTemp(actuatorTargetTempVal);
            }

            // Sync UI with internal state
            updateState(ChurchToolsHeatingBindingConstants.CHANNEL_HEATING_ENABLED, heatingEnabled ? OnOffType.ON : OnOffType.OFF);
            updateState(ChurchToolsHeatingBindingConstants.CHANNEL_SUMMER_MODE, summerMode ? OnOffType.ON : OnOffType.OFF);

        } catch (Exception e) {
            logger.error("Error evaluating heating logic: ", e);
        }
    }

    private void updateHvacMode(java.math.BigDecimal newMode) {
        if (this.expectedHvacMode == null || newMode.compareTo(this.expectedHvacMode) != 0) {
            this.expectedHvacMode = newMode;
            DecimalType command = new DecimalType(newMode);
            updateState(ChurchToolsHeatingBindingConstants.CHANNEL_HVAC_MODE, command);
            getCallback().postCommand(new ChannelUID(getThing().getUID(), ChurchToolsHeatingBindingConstants.CHANNEL_HVAC_MODE), command);
            sendCommandToConfiguredItem("knxHvacModeItem", ChurchToolsHeatingBindingConstants.CHANNEL_HVAC_MODE, command);
            logger.info("Room Heater HVAC Mode changed to {}", newMode);
        }
    }

    private void updateActuatorTargetTemp(double newTemp) {
        if (this.expectedActuatorTargetTemp == null || Math.abs(this.expectedActuatorTargetTemp - newTemp) > 0.01) {
            this.expectedActuatorTargetTemp = newTemp;
            QuantityType<?> command = new QuantityType<>(newTemp + " °C");
            updateState(ChurchToolsHeatingBindingConstants.CHANNEL_ACTUATOR_TARGET_TEMP, command);
            getCallback().postCommand(new ChannelUID(getThing().getUID(), ChurchToolsHeatingBindingConstants.CHANNEL_ACTUATOR_TARGET_TEMP), command);
            sendCommandToConfiguredItem("knxTargetTempItem", ChurchToolsHeatingBindingConstants.CHANNEL_ACTUATOR_TARGET_TEMP, new DecimalType(newTemp));
            logger.info("Room Heater Actuator Target Temp changed to {} °C", newTemp);
        }
    }

    private void updateRelay(OnOffType newState) {
        if (this.relayState != newState) {
            this.relayState = newState;
            updateState(ChurchToolsHeatingBindingConstants.CHANNEL_HEATING_RELAY, newState);
            getCallback().postCommand(new ChannelUID(getThing().getUID(), ChurchToolsHeatingBindingConstants.CHANNEL_HEATING_RELAY), newState);
            if (newState == OnOffType.ON) {
                updateState(ChurchToolsHeatingBindingConstants.CHANNEL_ACTUAL_HEATING_START, new DateTimeType(ZonedDateTime.now()));
            } else {
                updateState(ChurchToolsHeatingBindingConstants.CHANNEL_ACTUAL_HEATING_START, UnDefType.UNDEF);
            }
            logger.info("Room Heater Relay changed to {}", newState);
        }
    }
}
