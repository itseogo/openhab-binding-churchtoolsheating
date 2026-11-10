package org.openhab.binding.churchtoolsheating.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openhab.core.items.events.ItemStateChangedEvent;

public class ChurchToolsHandlerFactoryTest {

    @Test
    public void testFactorySupportsThingTypes() {
        ChurchToolsHandlerFactory factory = new ChurchToolsHandlerFactory();

        assertTrue(factory.supportsThingType(ChurchToolsHeatingBindingConstants.THING_TYPE_API));
        assertTrue(factory.supportsThingType(ChurchToolsHeatingBindingConstants.THING_TYPE_ROOM_HEATER));
    }

    @Test
    public void testEventSubscriberListensStrictlyToStateChangedEvent() {
        ChurchToolsHandlerFactory factory = new ChurchToolsHandlerFactory();

        Set<String> subscribed = factory.getSubscribedEventTypes();
        assertNotNull(subscribed);
        assertEquals(1, subscribed.size(), "Factory must subscribe ONLY to ItemStateChangedEvent to prevent command feedback loops!");
        assertTrue(subscribed.contains(ItemStateChangedEvent.TYPE));
    }
}

