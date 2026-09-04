package org.openhab.binding.churchtoolsheating.internal;

import java.util.Collections;
import java.util.List;

import org.openhab.core.config.discovery.AbstractDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.ThingUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChurchToolsDiscoveryService extends AbstractDiscoveryService {

    private final Logger logger = LoggerFactory.getLogger(ChurchToolsDiscoveryService.class);
    private ChurchToolsAPI api;
    private ThingUID bridgeUID;
    private Integer filterTypeId;

    public ChurchToolsDiscoveryService(ThingUID bridgeUID, ChurchToolsAPI api, Integer filterTypeId) {
        super(Collections.singleton(ChurchToolsHeatingBindingConstants.THING_TYPE_ROOM_HEATER), 10, true);
        this.api = api;
        this.bridgeUID = bridgeUID;
        this.filterTypeId = filterTypeId;
    }

    public void start() {
        super.activate(new java.util.HashMap<>());
    }

    public void stop() {
        super.deactivate();
    }
    
    public void triggerScan() {
        startScan();
    }

    @Override
    protected void startScan() {
        if (api == null) {
            logger.warn("Cannot discover rooms because ChurchTools API is not initialized");
            return;
        }
        
        try {
            List<ChurchToolsAPI.Resource> resources = api.getResources(filterTypeId);
            for (ChurchToolsAPI.Resource res : resources) {
                ThingUID uid = new ThingUID(ChurchToolsHeatingBindingConstants.THING_TYPE_ROOM_HEATER, bridgeUID, String.valueOf(res.id));
                DiscoveryResult result = DiscoveryResultBuilder.create(uid)
                        .withProperty("resourceId", res.id)
                        .withLabel("Raum: " + res.name)
                        .withBridge(bridgeUID)
                        .build();
                thingDiscovered(result);
            }
        } catch (Exception e) {
            logger.error("Failed to discover ChurchTools resources: {}", e.getMessage(), e);
        }
    }
}
