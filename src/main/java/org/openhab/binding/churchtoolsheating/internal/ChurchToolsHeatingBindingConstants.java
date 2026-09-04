package org.openhab.binding.churchtoolsheating.internal;

import org.openhab.core.thing.ThingTypeUID;

public class ChurchToolsHeatingBindingConstants {

    public static final String BINDING_ID = "churchtoolsheating";

    // Thing Types
    public static final ThingTypeUID THING_TYPE_API = new ThingTypeUID(BINDING_ID, "api");
    public static final ThingTypeUID THING_TYPE_ROOM_HEATER = new ThingTypeUID(BINDING_ID, "roomHeater");

    // Channels
    public static final String CHANNEL_CURRENT_TEMPERATURE = "currentTemperature";
    public static final String CHANNEL_TARGET_TEMPERATURE = "targetTemperature";
    public static final String CHANNEL_HEATING_RELAY = "heatingRelay";
    public static final String CHANNEL_NEXT_EVENT_START = "nextEventStart";
    public static final String CHANNEL_NEXT_EVENT_END = "nextEventEnd";
    public static final String CHANNEL_HEATING_START = "heatingStart";
    public static final String CHANNEL_ACTUAL_HEATING_START = "actualHeatingStart";
    public static final String CHANNEL_HEATING_ENABLED = "heatingEnabled";
    public static final String CHANNEL_SUMMER_MODE = "summerMode";
    public static final String CHANNEL_HVAC_MODE = "hvacMode";
    public static final String CHANNEL_ACTUATOR_TARGET_TEMP = "actuatorTargetTemp";
    public static final String CHANNEL_MANUAL_OVERRIDE_UNTIL = "manualOverrideUntil";
    public static final String CHANNEL_UPCOMING_EVENT_1 = "upcomingEvent1";
    public static final String CHANNEL_UPCOMING_EVENT_2 = "upcomingEvent2";
    public static final String CHANNEL_UPCOMING_EVENT_3 = "upcomingEvent3";
    public static final String CHANNEL_NEXT_EVENT_2_START = "nextEvent2Start";
    public static final String CHANNEL_NEXT_EVENT_2_END = "nextEvent2End";
    public static final String CHANNEL_NEXT_EVENT_3_START = "nextEvent3Start";
    public static final String CHANNEL_NEXT_EVENT_3_END = "nextEvent3End";

}
