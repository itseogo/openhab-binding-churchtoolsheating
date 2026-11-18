package org.openhab.binding.churchtoolsheating.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.events.ItemCommandEvent;
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
import org.openhab.core.thing.link.ItemChannelLinkRegistry;
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

        // 4. Actuator reports back the lower off-temperature (12 °C / 17 °C) from KNX bus:
        // Must NOT clear manual override!
        reset(callback);
        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_ACTUATOR_TARGET_TEMP, new DecimalType(17.0), false);
        verify(callback, never()).stateUpdated(
            eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_MANUAL_OVERRIDE_UNTIL)),
            eq(UnDefType.UNDEF)
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
            eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_HEATING_RELAY)),
            eq(OnOffType.ON)
        );
        // Ensure no echo postCommand on HVAC_MODE to avoid infinite command storms
        verify(callback, never()).postCommand(
            eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_HVAC_MODE)),
            any()
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

    @Test
    public void testNoLoopOnIdenticalHvacModeOrDisplayChannels() throws Exception {
        when(api.getBookings(eq(5), anyBoolean())).thenReturn(new ArrayList<>());
        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_TARGET_TEMPERATURE, new DecimalType(21.0));

        // Now the room is initialized and in idle offHvacMode (3)
        reset(callback);

        // Sending mode 3 again (e.g. from KNX bus update or echo) must NOT trigger any postCommand or state change
        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_HVAC_MODE, new DecimalType(3), false);

        verify(callback, never()).postCommand(any(), any());
        verify(callback, never()).stateUpdated(eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_HVAC_MODE)), any());

        // Sending an update to a display channel (e.g. upcomingEvent1) must NOT trigger heating evaluation or commands
        reset(callback);
        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_UPCOMING_EVENT_1, new StringType("Gottesdienst"), false);

        verify(callback, never()).postCommand(any(), any());
        verify(callback, never()).stateUpdated(any(), any());
    }

    @Test
    public void testDirectKnxItemCommandsViaEventPublisher() throws Exception {
        EventPublisher eventPublisher = mock(EventPublisher.class);
        handler.setEventPublisher(eventPublisher);

        config.put("knxHvacModeItem", "EK-Heizung-Betriebsart");
        config.put("knxTargetTempItem", "Temperatur EK soll");

        List<ChurchToolsAPI.Booking> bookings = new ArrayList<>();
        ChurchToolsAPI.Booking activeBooking = new ChurchToolsAPI.Booking();
        activeBooking.id = 999;
        activeBooking.caption = "TESTBuchung";
        activeBooking.startDate = ZonedDateTime.now().plusMinutes(10);
        activeBooking.endDate = ZonedDateTime.now().plusHours(2);
        bookings.add(activeBooking);
        when(api.getBookings(eq(5), anyBoolean())).thenReturn(bookings);

        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_TARGET_TEMPERATURE, new DecimalType(21.5));

        // Verify direct command sent to EK-Heizung-Betriebsart (Komfort = 1)
        ArgumentCaptor<ItemCommandEvent> eventCaptor = ArgumentCaptor.forClass(ItemCommandEvent.class);
        verify(eventPublisher, atLeastOnce()).post(eventCaptor.capture());

        boolean foundHvacModeCmd = false;
        boolean foundTargetTempCmd = false;
        for (ItemCommandEvent event : eventCaptor.getAllValues()) {
            if ("EK-Heizung-Betriebsart".equals(event.getItemName()) && new DecimalType(1).equals(event.getItemCommand())) {
                foundHvacModeCmd = true;
            }
            if ("Temperatur EK soll".equals(event.getItemName()) && new DecimalType(21.5).equals(event.getItemCommand())) {
                foundTargetTempCmd = true;
            }
        }
        assertTrue(foundHvacModeCmd, "Direct command to EK-Heizung-Betriebsart was not posted via EventPublisher");
        assertTrue(foundTargetTempCmd, "Direct command to Temperatur EK soll was not posted via EventPublisher");
    }

    @Test
    public void testStartupSyncReadsPersistedTargetAndCurrentTemp() throws Exception {
        EventPublisher eventPublisher = mock(EventPublisher.class);
        ItemChannelLinkRegistry linkRegistry = mock(ItemChannelLinkRegistry.class);
        ItemRegistry itemRegistry = mock(ItemRegistry.class);

        handler.setEventPublisher(eventPublisher);
        handler.setItemChannelLinkRegistry(linkRegistry);
        handler.setItemRegistry(itemRegistry);

        config.put("knxHvacModeItem", "EK-Heizung-Betriebsart");
        config.put("knxTargetTempItem", "Temperatur EK soll");
        config.put("knxCurrentTempItem", "Temperatur EK Ist");

        // Mock persisted targetTemperature item (21.5 °C) linked to CHANNEL_TARGET_TEMPERATURE
        Item targetTempItem = mock(Item.class);
        when(targetTempItem.getState()).thenReturn(new DecimalType(21.5));
        when(linkRegistry.getLinkedItems(eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_TARGET_TEMPERATURE))))
                .thenReturn(Collections.singleton(targetTempItem));

        // Mock persisted currentTemp item (12.8 °C) in ItemRegistry
        Item currentTempItem = mock(Item.class);
        when(currentTempItem.getState()).thenReturn(new DecimalType(12.8));
        when(itemRegistry.getItem("Temperatur EK Ist")).thenReturn(currentTempItem);

        // User clicks Komfort (1) right after openHAB restart WITHOUT having moved the Sollwert Vorlauf slider!
        when(api.getBookings(eq(5), anyBoolean())).thenReturn(new ArrayList<>());
        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_HVAC_MODE, new DecimalType(1), true);

        // Must send 21.5 °C (from persisted item), NOT the hardcoded 20.0 °C default!
        ArgumentCaptor<ItemCommandEvent> eventCaptor = ArgumentCaptor.forClass(ItemCommandEvent.class);
        verify(eventPublisher, atLeastOnce()).post(eventCaptor.capture());

        boolean sent21_5 = false;
        for (ItemCommandEvent event : eventCaptor.getAllValues()) {
            if ("Temperatur EK soll".equals(event.getItemName()) && new DecimalType(21.5).equals(event.getItemCommand())) {
                sent21_5 = true;
            }
        }
        assertTrue(sent21_5, "Binding should have synced 21.5 °C from persisted targetTemperature item instead of sending 20.0 °C");
    }

    @Test
    public void testFrostModeInIdleRoom_DoesNotFlipFlopBackToNacht() throws Exception {
        when(api.getBookings(eq(5), anyBoolean())).thenReturn(new ArrayList<>());
        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_TARGET_TEMPERATURE, new DecimalType(21.0));

        // Room is now in idle state (offHvacMode = 3 Nacht)
        reset(callback);

        // User clicks Frost (4) on widget (or KNX wall switch reports Frost 4)
        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_HVAC_MODE, new DecimalType(4), true);

        // Binding must update channel state to 4 (Frost) and MUST NEVER send postCommand(3) back!
        verify(callback, never()).postCommand(
            eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_HVAC_MODE)),
            eq(new DecimalType(3))
        );
        verify(callback, never()).stateUpdated(
            eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_HVAC_MODE)),
            eq(new DecimalType(3))
        );
    }

    @Test
    public void testStartupIdleClearsStuckRelayAndActualHeatingStart() throws Exception {
        when(api.getBookings(eq(5), anyBoolean())).thenReturn(new ArrayList<>());

        // On first evaluation after startup/JAR update when idle (no active event):
        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_TARGET_TEMPERATURE, new DecimalType(21.0));

        // Because relayState starts as null, it MUST explicitly set heatingRelay = OFF and actualHeatingStart = UNDEF
        verify(callback, atLeastOnce()).stateUpdated(
            eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_HEATING_RELAY)),
            eq(OnOffType.OFF)
        );
        verify(callback, atLeastOnce()).stateUpdated(
            eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_ACTUAL_HEATING_START)),
            eq(UnDefType.UNDEF)
        );
    }

    @Test
    public void testManualOverrideUntil_IgnoresSelfStateChangeEcho_AcceptsExplicitReset() throws Exception {
        List<ChurchToolsAPI.Booking> bookings = new ArrayList<>();
        ChurchToolsAPI.Booking activeBooking = new ChurchToolsAPI.Booking();
        activeBooking.id = 777;
        activeBooking.caption = "Jugendkreis";
        activeBooking.startDate = ZonedDateTime.now().minusMinutes(30);
        activeBooking.endDate = ZonedDateTime.now().plusHours(2);
        bookings.add(activeBooking);
        when(api.getBookings(eq(5), anyBoolean())).thenReturn(bookings);

        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_TARGET_TEMPERATURE, new DecimalType(21.0));

        // User activates manual OFF override (Nacht = 3) during active event
        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_HVAC_MODE, new DecimalType(3), false);
        reset(callback);

        // 1. An in-flight ItemStateChangedEvent (isExplicitCommand = false) with UNDEF on CHANNEL_MANUAL_OVERRIDE_UNTIL
        // MUST BE IGNORED and must NOT reset the active manual override or turn heating back on!
        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_MANUAL_OVERRIDE_UNTIL, UnDefType.UNDEF, false);

        verify(callback, never()).postCommand(
            eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_HEATING_RELAY)),
            eq(OnOffType.ON)
        );
        verify(callback, never()).stateUpdated(
            eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_MANUAL_OVERRIDE_UNTIL)),
            eq(UnDefType.UNDEF)
        );

        // 2. An explicit command (isExplicitCommand = true) from user clicking Reset MUST reset manual override and resume heating!
        handler.processChannelState(ChurchToolsHeatingBindingConstants.CHANNEL_MANUAL_OVERRIDE_UNTIL, UnDefType.UNDEF, true);

        verify(callback, atLeastOnce()).stateUpdated(
            eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_MANUAL_OVERRIDE_UNTIL)),
            eq(UnDefType.UNDEF)
        );
        verify(callback, atLeastOnce()).postCommand(
            eq(new ChannelUID(thingUID, ChurchToolsHeatingBindingConstants.CHANNEL_HEATING_RELAY)),
            eq(OnOffType.ON)
        );
    }
}
