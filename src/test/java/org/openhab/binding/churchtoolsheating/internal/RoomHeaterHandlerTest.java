package org.openhab.binding.churchtoolsheating.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

public class RoomHeaterHandlerTest {

    private Thing thing;
    private ThingHandlerCallback callback;
    private Bridge bridge;
    private ChurchToolsBridgeHandler bridgeHandler;
    private ChurchToolsAPI api;
    private Configuration config;
    private RoomHeaterHandler handler;
    private ThingUID thingUID;

    @BeforeEach
    public void setup() {
        thing = mock(Thing.class);
        callback = mock(ThingHandlerCallback.class);
        bridge = mock(Bridge.class);
        bridgeHandler = mock(ChurchToolsBridgeHandler.class);
        api = mock(ChurchToolsAPI.class);
        config = new Configuration();

        thingUID = new ThingUID("churchtoolsheating:roomHeater:bridge1:room1");
        when(thing.getUID()).thenReturn(thingUID);
        when(thing.getConfiguration()).thenReturn(config);
        when(thing.getBridgeUID()).thenReturn(new ThingUID("churchtoolsheating:api:bridge1"));

        when(bridge.getStatus()).thenReturn(ThingStatus.ONLINE);
        when(bridge.getHandler()).thenReturn(bridgeHandler);
        when(bridgeHandler.getApi()).thenReturn(api);
        when(bridgeHandler.getRefreshInterval()).thenReturn(5);

        // Standard test config
        config.put("resourceId", 5);
        config.put("enableExternalManualMode", true);
        config.put("externalManualModeDuration", 6);
        config.put("offTemperature", 17.0);
        config.put("offHvacMode", 3);

        handler = new RoomHeaterHandler(thing) {
            @Override
            public Bridge getBridge() {
                return bridge;
            }
        };
        handler.setCallback(callback);
    }

    @Test
    public void testGetConfigFallbacks() {
        config.put("doubleVal", "21.5");
        config.put("intVal", 6);
        config.put("boolVal", true);
        config.put("badVal", "not-a-number");

        assertEquals(21.5, handler.getDoubleConfig("doubleVal", 17.0));
        assertEquals(6, handler.getIntConfig("intVal", 3));
        assertTrue(handler.getBooleanConfig("boolVal", false));
        assertEquals(17.0, handler.getDoubleConfig("badVal", 17.0));
        assertEquals(10, handler.getIntConfig("missingVal", 10));
    }

    @Test
    public void testTemperatureInputsVariousTypes() {
        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_CURRENT_TEMPERATURE, new QuantityType<>("18.5 °C"));
        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_TARGET_TEMPERATURE, new DecimalType(21.0));
        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_CURRENT_TEMPERATURE, new DecimalType(19.0));
        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_CURRENT_TEMPERATURE, new StringType("19.5"));
    }

    @Test
    public void testIdleRoom_ManualKomfortTurnsHeatingOn() throws Exception {
        when(api.getBookings(eq(5), anyBoolean())).thenReturn(new ArrayList<>());

        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_HVAC_MODE, new DecimalType(1), false);

        verify(callback, atLeastOnce()).stateUpdated(
            eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_MANUAL_OVERRIDE_UNTIL)),
            any(DateTimeType.class)
        );
        verify(callback, atLeastOnce()).postCommand(
            eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_HEATING_RELAY)),
            eq(OnOffType.ON)
        );
    }

    @Test
    public void testIdleRoom_ManualNachtTurnsHeatingOff() throws Exception {
        when(api.getBookings(eq(5), anyBoolean())).thenReturn(new ArrayList<>());

        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_HVAC_MODE, new DecimalType(1), false);
        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_HVAC_MODE, new DecimalType(3), false);

        verify(callback, atLeastOnce()).stateUpdated(
            eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_MANUAL_OVERRIDE_UNTIL)),
            eq(UnDefType.UNDEF)
        );
        verify(callback, atLeastOnce()).postCommand(
            eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_HEATING_RELAY)),
            eq(OnOffType.OFF)
        );
    }

    @Test
    public void testActiveEvent_TurningToNachtForcesManualOffOverrideWithoutBouncing() throws Exception {
        List<ChurchToolsAPI.Booking> bookings = new ArrayList<>();
        ChurchToolsAPI.Booking activeBooking = new ChurchToolsAPI.Booking();
        activeBooking.id = 101;
        activeBooking.caption = "Gottesdienst";
        activeBooking.startDate = ZonedDateTime.now().minusHours(1);
        activeBooking.endDate = ZonedDateTime.now().plusHours(2);
        bookings.add(activeBooking);
        when(api.getBookings(eq(5), anyBoolean())).thenReturn(bookings);

        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_TARGET_TEMPERATURE, new DecimalType(21.0));

        verify(callback, atLeastOnce()).postCommand(
            eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_HEATING_RELAY)),
            eq(OnOffType.ON)
        );

        reset(callback);

        // User switches to Nacht (3) during active event!
        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_HVAC_MODE, new DecimalType(3), false);

        // 1. Manual override must be set to 6h OFF override (DateTimeType, NOT UNDEF!)
        verify(callback, atLeastOnce()).stateUpdated(
            eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_MANUAL_OVERRIDE_UNTIL)),
            any(DateTimeType.class)
        );
        // 2. Relay turned OFF!
        verify(callback, atLeastOnce()).postCommand(
            eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_HEATING_RELAY)),
            eq(OnOffType.OFF)
        );
        // 3. Automatik must NEVER override back to Komfort (1)!
        verify(callback, never()).postCommand(
            eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_HVAC_MODE)),
            eq(new DecimalType(1))
        );
    }

    @Test
    public void testActiveEvent_ResumingFromManualOffWithKomfort() throws Exception {
        List<ChurchToolsAPI.Booking> bookings = new ArrayList<>();
        ChurchToolsAPI.Booking activeBooking = new ChurchToolsAPI.Booking();
        activeBooking.id = 101;
        activeBooking.caption = "Seminar";
        activeBooking.startDate = ZonedDateTime.now().minusHours(1);
        activeBooking.endDate = ZonedDateTime.now().plusHours(2);
        bookings.add(activeBooking);
        when(api.getBookings(eq(5), anyBoolean())).thenReturn(bookings);

        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_TARGET_TEMPERATURE, new DecimalType(21.0));
        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_HVAC_MODE, new DecimalType(3), false);

        reset(callback);

        // User turns Komfort (1) back on!
        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_HVAC_MODE, new DecimalType(1), false);

        verify(callback, atLeastOnce()).stateUpdated(
            eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_MANUAL_OVERRIDE_UNTIL)),
            eq(UnDefType.UNDEF)
        );
        verify(callback, atLeastOnce()).postCommand(
            eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_HEATING_RELAY)),
            eq(OnOffType.ON)
        );
    }

    @Test
    public void testDisablingHeatingAutomation_HandsOffNoKnXCommands() throws Exception {
        when(api.getBookings(eq(5), anyBoolean())).thenReturn(new ArrayList<>());
        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_TARGET_TEMPERATURE, new DecimalType(21.0));

        reset(callback);

        // User turns Heizautomatik OFF
        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_HEATING_ENABLED, OnOffType.OFF);

        verify(callback, atLeastOnce()).stateUpdated(
            eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_HEATING_ENABLED)),
            eq(OnOffType.OFF)
        );
        // ABSOLUTE HANDS-OFF: No outgoing commands sent
        verify(callback, never()).postCommand(
            eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_HEATING_RELAY)),
            any()
        );
        verify(callback, never()).postCommand(
            eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_HVAC_MODE)),
            any()
        );
        verify(callback, never()).postCommand(
            eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_ACTUATOR_TARGET_TEMP)),
            any()
        );
    }

    @Test
    public void testExplicitCommandFromWidget_AlwaysAccepted() throws Exception {
        config.put("enableExternalManualMode", false);
        when(api.getBookings(eq(5), anyBoolean())).thenReturn(new ArrayList<>());

        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_HVAC_MODE, new DecimalType(1), true);

        verify(callback, atLeastOnce()).stateUpdated(
            eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_MANUAL_OVERRIDE_UNTIL)),
            any(DateTimeType.class)
        );
        verify(callback, atLeastOnce()).postCommand(
            eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_HVAC_MODE)),
            eq(new DecimalType(1))
        );
    }

    @Test
    public void testPreheatCalculationCurve() throws Exception {
        List<ChurchToolsAPI.Booking> bookings = new ArrayList<>();
        ChurchToolsAPI.Booking futureBooking = new ChurchToolsAPI.Booking();
        futureBooking.id = 200;
        futureBooking.caption = "Probe";
        ZonedDateTime eventStart = ZonedDateTime.now().plusHours(10);
        futureBooking.startDate = eventStart;
        futureBooking.endDate = eventStart.plusHours(2);
        bookings.add(futureBooking);
        when(api.getBookings(eq(5), anyBoolean())).thenReturn(bookings);

        config.put("deltaT1", 0.5);
        config.put("deltaT2", 1.0);
        config.put("deltaT3", 2.0);
        config.put("deltaT4", 4.0);
        config.put("vorlauf1", 1.0);
        config.put("vorlauf2", 2.5);
        config.put("vorlauf3", 5.0);
        config.put("vorlauf4", 8.0);

        // Target: 21 °C, Current: 19 °C -> DeltaT = 2.0 °C (Exact point 3 -> vorlauf3 = 5.0h)
        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_TARGET_TEMPERATURE, new DecimalType(21.0));
        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_CURRENT_TEMPERATURE, new DecimalType(19.0));

        ArgumentCaptor<State> stateCaptor = ArgumentCaptor.forClass(State.class);
        verify(callback, atLeastOnce()).stateUpdated(
            eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_HEATING_START)),
            stateCaptor.capture()
        );

        DateTimeType heatingStart = (DateTimeType) stateCaptor.getValue();
        assertNotNull(heatingStart);
        // Expected vorlauf is 5 hours before eventStart
        assertEquals(eventStart.minusHours(5).toEpochSecond(), heatingStart.getZonedDateTime().toEpochSecond(), 60);
    }

    @Test
    public void testMaxEventDurationFilterIgnoredWhenLong() throws Exception {
        List<ChurchToolsAPI.Booking> bookings = new ArrayList<>();
        ChurchToolsAPI.Booking multiDayBooking = new ChurchToolsAPI.Booking();
        multiDayBooking.id = 300;
        multiDayBooking.caption = "Dauerbuchung 3 Tage";
        multiDayBooking.startDate = ZonedDateTime.now().minusHours(1);
        multiDayBooking.endDate = ZonedDateTime.now().plusHours(48); // 49 hours long!
        bookings.add(multiDayBooking);
        when(api.getBookings(eq(5), anyBoolean())).thenReturn(bookings);

        config.put("enableMaxDurationFilter", true);
        config.put("maxEventDuration", 10); // max 10 hours

        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_TARGET_TEMPERATURE, new DecimalType(21.0));

        // Long event filtered out -> Room must NEVER turn relay ON!
        verify(callback, never()).postCommand(
            eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_HEATING_RELAY)),
            eq(OnOffType.ON)
        );
    }
}
