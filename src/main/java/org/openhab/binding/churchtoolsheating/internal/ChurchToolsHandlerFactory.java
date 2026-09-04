package org.openhab.binding.churchtoolsheating.internal;

import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.service.component.annotations.Component;

@Component(service = ThingHandlerFactory.class, immediate = true)
public class ChurchToolsHandlerFactory extends BaseThingHandlerFactory {

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
            return new RoomHeaterHandler(thing);
        }

        return null;
    }
}
