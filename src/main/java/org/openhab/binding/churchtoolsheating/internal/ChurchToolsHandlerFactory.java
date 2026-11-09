package org.openhab.binding.churchtoolsheating.internal;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.openhab.core.events.Event;
import org.openhab.core.events.EventSubscriber;
import org.openhab.core.items.events.ItemStateChangedEvent;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;
import org.openhab.core.types.State;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(service = { ThingHandlerFactory.class, EventSubscriber.class }, immediate = true)
public class ChurchToolsHandlerFactory extends BaseThingHandlerFactory implements EventSubscriber {

    private final Logger logger = LoggerFactory.getLogger(ChurchToolsHandlerFactory.class);
    private final Map<ThingUID, RoomHeaterHandler> activeHandlers = new ConcurrentHashMap<>();

    private volatile ItemChannelLinkRegistry linkRegistry;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    public void setItemChannelLinkRegistry(ItemChannelLinkRegistry linkRegistry) {
        this.linkRegistry = linkRegistry;
    }

    public void unsetItemChannelLinkRegistry(ItemChannelLinkRegistry linkRegistry) {
        if (this.linkRegistry == linkRegistry) {
            this.linkRegistry = null;
        }
    }

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return ChurchToolsHeatingBindingConstants.THING_TYPE_API.equals(thingTypeUID)
                || ChurchToolsHeatingBindingConstants.THING_TYPE_ROOM_HEATER.equals(thingTypeUID);
    }

    @Override
    protected ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();

        if (ChurchToolsHeatingBindingConstants.THING_TYPE_API.equals(thingTypeUID)) {
            return new ChurchToolsBridgeHandler((Bridge) thing);
        } else if (ChurchToolsHeatingBindingConstants.THING_TYPE_ROOM_HEATER.equals(thingTypeUID)) {
            RoomHeaterHandler handler = new RoomHeaterHandler(thing);
            activeHandlers.put(thing.getUID(), handler);
            return handler;
        }

        return null;
    }

    @Override
    protected void removeHandler(ThingHandler thingHandler) {
        if (thingHandler instanceof RoomHeaterHandler) {
            activeHandlers.remove(thingHandler.getThing().getUID());
        }
        super.removeHandler(thingHandler);
    }

    @Override
    public Set<String> getSubscribedEventTypes() {
        return Collections.singleton(ItemStateChangedEvent.TYPE);
    }

    @Override
    public void receive(Event event) {
        if (event instanceof ItemStateChangedEvent) {
            ItemStateChangedEvent stateEvent = (ItemStateChangedEvent) event;
            String itemName = stateEvent.getItemName();
            State newState = stateEvent.getItemState();

            ItemChannelLinkRegistry registry = this.linkRegistry;
            if (registry != null) {
                Set<ChannelUID> boundChannels = registry.getBoundChannels(itemName);
                if (boundChannels != null && !boundChannels.isEmpty()) {
                    for (ChannelUID channelUID : boundChannels) {
                        RoomHeaterHandler handler = activeHandlers.get(channelUID.getThingUID());
                        if (handler != null) {
                            handler.processChannelState(channelUID.getId(), newState);
                        }
                    }
                }
            }

            for (RoomHeaterHandler handler : activeHandlers.values()) {
                handler.onItemNameStateChanged(itemName, newState);
            }
        }
    }
}
