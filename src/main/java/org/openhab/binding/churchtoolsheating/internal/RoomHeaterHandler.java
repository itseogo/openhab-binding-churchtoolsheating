package org.openhab.binding.churchtoolsheating.internal;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RoomHeaterHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(RoomHeaterHandler.class);
    
    private ScheduledFuture<?> pollingJob;
    private double currentTemp = 20.0;
    private double targetTemp = 20.0;
    private boolean summerMode = false;
    private boolean heatingEnabled = true;
    private OnOffType relayState = OnOffType.OFF;

    private ZonedDateTime manualOverrideUntil = null;
    private java.math.BigDecimal expectedHvacMode = null;
    private Double expectedActuatorTargetTemp = null;
    private boolean previousHeatingEnabled = true;
    private boolean previousSummerMode = false;
    private OnOffType previousTargetRelayState = OnOffType.OFF;
    
    private List<ChurchToolsAPI.Booking> cachedBookings = null;
    private long lastApiFetchTime = 0;

    public RoomHeaterHandler(Thing thing) {
        super(thing);
    }

    private int getIntConfig(String key, int defaultVal) {
        Object val = getConfig().get(key);
        if (val == null) return defaultVal;
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) {
            try { return Integer.parseInt((String) val); } catch (NumberFormatException e) {}
        }
        return defaultVal;
    }

    private boolean getBooleanConfig(String key, boolean defaultVal) {
        Object val = getConfig().get(key);
        if (val == null) return defaultVal;
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof String) return Boolean.parseBoolean((String) val);
        return defaultVal;
    }

    private double getDoubleConfig(String key, double defaultVal) {
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
        logger.debug("Initializing Room Heater Handler");
        updateStatus(ThingStatus.ONLINE);
        
        int refreshInterval = 15;
        if (getBridge() != null && getBridge().getHandler() instanceof ChurchToolsBridgeHandler) {
            refreshInterval = ((ChurchToolsBridgeHandler) getBridge().getHandler()).getRefreshInterval();
        }
        
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
            State state = (State) command;
            switch (channelUID.getId()) {
                case ChurchToolsHeatingBindingConstants.CHANNEL_CURRENT_TEMPERATURE:
                    if (state instanceof QuantityType) {
                        currentTemp = ((QuantityType<?>) state).toBigDecimal().doubleValue();
                    }
                    break;
                case ChurchToolsHeatingBindingConstants.CHANNEL_TARGET_TEMPERATURE:
                    if (state instanceof QuantityType) {
                        targetTemp = ((QuantityType<?>) state).toBigDecimal().doubleValue();
                    }
                    break;
                case ChurchToolsHeatingBindingConstants.CHANNEL_SUMMER_MODE:
                    if (state instanceof OnOffType) {
                        summerMode = state == OnOffType.ON;
                    } else if (state instanceof org.openhab.core.library.types.StringType) {
                        summerMode = state.toString().equalsIgnoreCase("ON");
                    }
                    updateState(ChurchToolsHeatingBindingConstants.CHANNEL_SUMMER_MODE, summerMode ? OnOffType.ON : OnOffType.OFF);
                    break;
                case ChurchToolsHeatingBindingConstants.CHANNEL_HEATING_ENABLED:
                    if (state instanceof OnOffType || state instanceof org.openhab.core.library.types.StringType) {
                        heatingEnabled = state.toString().equalsIgnoreCase("ON");
                        // Reset any active manual override when automation is toggled
                        manualOverrideUntil = null;
                        updateState(ChurchToolsHeatingBindingConstants.CHANNEL_MANUAL_OVERRIDE_UNTIL, org.openhab.core.types.UnDefType.UNDEF);
                    }
                    updateState(ChurchToolsHeatingBindingConstants.CHANNEL_HEATING_ENABLED, heatingEnabled ? OnOffType.ON : OnOffType.OFF);
                    break;
                case ChurchToolsHeatingBindingConstants.CHANNEL_MANUAL_OVERRIDE_UNTIL:
                    if (state instanceof org.openhab.core.types.UnDefType || (state instanceof org.openhab.core.library.types.StringType && state.toString().equals("UNDEF"))) {
                        manualOverrideUntil = null;
                        updateState(ChurchToolsHeatingBindingConstants.CHANNEL_MANUAL_OVERRIDE_UNTIL, org.openhab.core.types.UnDefType.UNDEF);
                        logger.info("Manual override was explicitly reset by user.");
                    }
                    break;
                case ChurchToolsHeatingBindingConstants.CHANNEL_HVAC_MODE:
                    java.math.BigDecimal newMode = null;
                    if (state instanceof org.openhab.core.library.types.DecimalType) {
                        newMode = ((org.openhab.core.library.types.DecimalType) state).toBigDecimal();
                    } else if (state instanceof org.openhab.core.library.types.StringType) {
                        try {
                            newMode = new java.math.BigDecimal(state.toString());
                        } catch (NumberFormatException e) {}
                    }
                    
                    if (newMode != null) {
                        boolean enableExternalManualMode = getBooleanConfig("enableExternalManualMode", false);
                        if (enableExternalManualMode && expectedHvacMode != null && newMode.compareTo(expectedHvacMode) != 0) {
                            int overrideHours = getIntConfig("externalManualModeDuration", 6);
                            manualOverrideUntil = ZonedDateTime.now().plusHours(overrideHours);
                            updateState(ChurchToolsHeatingBindingConstants.CHANNEL_MANUAL_OVERRIDE_UNTIL, new DateTimeType(manualOverrideUntil));
                            logger.info("External HVAC override detected for resource {}. Suspending automation for {} hours until {}", getConfig().get("resourceId"), overrideHours, manualOverrideUntil);
                        }
                        expectedHvacMode = newMode;
                        updateState(ChurchToolsHeatingBindingConstants.CHANNEL_HVAC_MODE, new org.openhab.core.library.types.DecimalType(newMode));
                    }
                    break;
            }
            // Re-evaluate logic if inputs change
            evaluateHeatingLogic();
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

            ChurchToolsBridgeHandler bridgeHandler = (ChurchToolsBridgeHandler) getBridge().getHandler();
            ChurchToolsAPI api = bridgeHandler.getApi();
            if (api == null) {
                return;
            }
            
            int resourceId = getIntConfig("resourceId", 0);
            boolean onlyConfirmed = getBooleanConfig("onlyConfirmedBookings", true);

            int refreshInterval = 15;
            if (getBridge() != null && getBridge().getHandler() instanceof ChurchToolsBridgeHandler) {
                refreshInterval = ((ChurchToolsBridgeHandler) getBridge().getHandler()).getRefreshInterval();
            }
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

            // Read new configuration for max duration filtering
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

            updateState(ChurchToolsHeatingBindingConstants.CHANNEL_UPCOMING_EVENT_1, new org.openhab.core.library.types.StringType(event1));
            updateState(ChurchToolsHeatingBindingConstants.CHANNEL_UPCOMING_EVENT_2, new org.openhab.core.library.types.StringType(event2));
            updateState(ChurchToolsHeatingBindingConstants.CHANNEL_UPCOMING_EVENT_3, new org.openhab.core.library.types.StringType(event3));

            if (upcoming.size() > 1) {
                updateState(ChurchToolsHeatingBindingConstants.CHANNEL_NEXT_EVENT_2_START, new DateTimeType(upcoming.get(1).startDate));
                updateState(ChurchToolsHeatingBindingConstants.CHANNEL_NEXT_EVENT_2_END, new DateTimeType(upcoming.get(1).endDate));
            } else {
                updateState(ChurchToolsHeatingBindingConstants.CHANNEL_NEXT_EVENT_2_START, org.openhab.core.types.UnDefType.UNDEF);
                updateState(ChurchToolsHeatingBindingConstants.CHANNEL_NEXT_EVENT_2_END, org.openhab.core.types.UnDefType.UNDEF);
            }

            if (upcoming.size() > 2) {
                updateState(ChurchToolsHeatingBindingConstants.CHANNEL_NEXT_EVENT_3_START, new DateTimeType(upcoming.get(2).startDate));
                updateState(ChurchToolsHeatingBindingConstants.CHANNEL_NEXT_EVENT_3_END, new DateTimeType(upcoming.get(2).endDate));
            } else {
                updateState(ChurchToolsHeatingBindingConstants.CHANNEL_NEXT_EVENT_3_START, org.openhab.core.types.UnDefType.UNDEF);
                updateState(ChurchToolsHeatingBindingConstants.CHANNEL_NEXT_EVENT_3_END, org.openhab.core.types.UnDefType.UNDEF);
            }

            // 1. Find valid event for heating (already filtered for max 10h duration)
            ZonedDateTime eventStart = null;
            ZonedDateTime eventEnd = null;

            if (upcoming.size() > 0) {
                eventStart = upcoming.get(0).startDate;
                eventEnd = upcoming.get(0).endDate;
            }

            if (eventStart == null) {
                updateRelay(OnOffType.OFF);
                updateHvacMode(java.math.BigDecimal.valueOf(getIntConfig("offHvacMode", 3)));
                updateActuatorTargetTemp(getDoubleConfig("offTemperature", 17.0));
                return;
            }
            
            updateState(ChurchToolsHeatingBindingConstants.CHANNEL_NEXT_EVENT_START, new DateTimeType(eventStart));
            updateState(ChurchToolsHeatingBindingConstants.CHANNEL_NEXT_EVENT_END, new DateTimeType(eventEnd));

            // 2. Calculate Pre-heat time (4-point curve)
            boolean enablePreheating = getBooleanConfig("enablePreheating", true);

            double vorlaufH = 0.0;

            if (enablePreheating) {
                double deltaT = targetTemp - currentTemp;
                if (deltaT < 0) deltaT = 0;

                double dt1 = ((BigDecimal) getConfig().get("deltaT1")).doubleValue();
                double dt2 = ((BigDecimal) getConfig().get("deltaT2")).doubleValue();
                double dt3 = ((BigDecimal) getConfig().get("deltaT3")).doubleValue();
                double dt4 = ((BigDecimal) getConfig().get("deltaT4")).doubleValue();

                double h1 = ((BigDecimal) getConfig().get("vorlauf1")).doubleValue();
                double h2 = ((BigDecimal) getConfig().get("vorlauf2")).doubleValue();
                double h3 = ((BigDecimal) getConfig().get("vorlauf3")).doubleValue();
                double h4 = ((BigDecimal) getConfig().get("vorlauf4")).doubleValue();

                if (deltaT <= dt1) {
                    vorlaufH = h1;
                } else if (deltaT <= dt2) {
                    vorlaufH = h1 + (h2 - h1) * ((deltaT - dt1) / (dt2 - dt1));
                } else if (deltaT <= dt3) {
                    vorlaufH = h2 + (h3 - h2) * ((deltaT - dt2) / (dt3 - dt2));
                } else if (deltaT <= dt4) {
                    vorlaufH = h3 + (h4 - h3) * ((deltaT - dt3) / (dt4 - dt3));
                } else {
                    vorlaufH = h4;
                }
            }

            int vorlaufInMinuten = (int) (vorlaufH * 60.0);
            ZonedDateTime vorlaufZeit = eventStart.minusMinutes(vorlaufInMinuten);
            updateState(ChurchToolsHeatingBindingConstants.CHANNEL_HEATING_START, new DateTimeType(vorlaufZeit));

            // 3. Control logic
            OnOffType targetRelayState;
            if (summerMode || !heatingEnabled) {
                targetRelayState = OnOffType.OFF;
            } else if (now.isAfter(vorlaufZeit) && now.isBefore(eventEnd)) {
                targetRelayState = OnOffType.ON;
            } else {
                targetRelayState = OnOffType.OFF;
            }

            // If a new heating phase just started, automatically reset any manual override
            if (targetRelayState == OnOffType.ON && previousTargetRelayState == OnOffType.OFF) {
                if (manualOverrideUntil != null) {
                    manualOverrideUntil = null;
                    updateState(ChurchToolsHeatingBindingConstants.CHANNEL_MANUAL_OVERRIDE_UNTIL, org.openhab.core.types.UnDefType.UNDEF);
                    logger.info("New event heating phase started. Automatically resetting manual override for resource {}.", getConfig().get("resourceId"));
                }
            }
            previousTargetRelayState = targetRelayState;

            // Determine target HVAC mode
            java.math.BigDecimal targetHvacMode = java.math.BigDecimal.valueOf(1); // 1 = Komfort
            double actuatorTargetTempVal = targetTemp;

            if (targetRelayState == OnOffType.OFF) {
                actuatorTargetTempVal = getDoubleConfig("offTemperature", 17.0);
                targetHvacMode = java.math.BigDecimal.valueOf(getIntConfig("offHvacMode", 3));
            }

            // Check if automation was just disabled or summer mode just activated
            boolean justDisabled = (previousHeatingEnabled && !heatingEnabled) || (!previousSummerMode && summerMode);
            previousHeatingEnabled = heatingEnabled;
            previousSummerMode = summerMode;

            if (justDisabled) {
                // Automation just disabled. Force it to offHvacMode and Relay OFF once.
                updateRelay(OnOffType.OFF);
                updateHvacMode(targetHvacMode);
                updateActuatorTargetTemp(actuatorTargetTempVal);
                logger.info("Heizautomatik disabled (or summer mode active). Forced HVAC to OFF mode once.");
            }

            if (!heatingEnabled || summerMode) {
                // Automation is disabled, we do not interfere with the KNX bus continuously.
                // Sync UI with internal state
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
                    updateState(ChurchToolsHeatingBindingConstants.CHANNEL_MANUAL_OVERRIDE_UNTIL, org.openhab.core.types.UnDefType.UNDEF);
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
            org.openhab.core.library.types.DecimalType command = new org.openhab.core.library.types.DecimalType(newMode);
            updateState(ChurchToolsHeatingBindingConstants.CHANNEL_HVAC_MODE, command);
            getCallback().postCommand(new ChannelUID(getThing().getUID(), ChurchToolsHeatingBindingConstants.CHANNEL_HVAC_MODE), command);
            logger.info("Room Heater HVAC Mode changed to {}", newMode);
        }
    }

    private void updateActuatorTargetTemp(double newTemp) {
        if (this.expectedActuatorTargetTemp == null || Math.abs(this.expectedActuatorTargetTemp - newTemp) > 0.01) {
            this.expectedActuatorTargetTemp = newTemp;
            QuantityType<?> command = new QuantityType<>(newTemp + " °C");
            updateState(ChurchToolsHeatingBindingConstants.CHANNEL_ACTUATOR_TARGET_TEMP, command);
            getCallback().postCommand(new ChannelUID(getThing().getUID(), ChurchToolsHeatingBindingConstants.CHANNEL_ACTUATOR_TARGET_TEMP), command);
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
                updateState(ChurchToolsHeatingBindingConstants.CHANNEL_ACTUAL_HEATING_START, org.openhab.core.types.UnDefType.UNDEF);
            }
            logger.info("Room Heater Relay changed to {}", newState);
        }
    }
}
