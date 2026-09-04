package org.openhab.binding.churchtoolsheating.internal;

import org.openhab.core.config.discovery.DiscoveryService;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.types.Command;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.openhab.core.config.core.Configuration;
import java.util.Hashtable;

public class ChurchToolsBridgeHandler extends BaseBridgeHandler {

    private final Logger logger = LoggerFactory.getLogger(ChurchToolsBridgeHandler.class);
    private ChurchToolsAPI api;
    private ChurchToolsDiscoveryService discoveryService;
    private ServiceRegistration<?> discoveryRegistration;

    public ChurchToolsBridgeHandler(Bridge bridge) {
        super(bridge);
    }

    @Override
    public void initialize() {
        logger.debug("Initializing ChurchTools API Bridge");
        Configuration config = getThing().getConfiguration();
        
        String url = (String) config.get("url");
        String token = (String) config.get("token");

        if (url == null || url.isEmpty() || token == null || token.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "URL und Token werden benötigt");
            return;
        }

        try {
            api = new ChurchToolsAPI(url, token);
            // Quick test connection
            api.getResources(null); 
            updateStatus(ThingStatus.ONLINE);
            
            Integer roomTypeId = null;
            Object roomTypeObj = config.get("roomResourceTypeId");
            if (roomTypeObj instanceof Number) {
                roomTypeId = ((Number) roomTypeObj).intValue();
            } else if (roomTypeObj instanceof String) {
                try {
                    roomTypeId = Integer.parseInt((String) roomTypeObj);
                } catch (NumberFormatException e) {}
            }

            // Register Discovery Service
            discoveryService = new ChurchToolsDiscoveryService(getThing().getUID(), api, roomTypeId);
            discoveryService.start();

            BundleContext bc = FrameworkUtil.getBundle(ChurchToolsBridgeHandler.class).getBundleContext();
            if (bc != null) {
                Hashtable<String, Object> props = new Hashtable<>();
                discoveryRegistration = bc.registerService(DiscoveryService.class.getName(), discoveryService, props);
                
                // Trigger an initial scan
                discoveryService.triggerScan();
            }
            
        } catch (Exception e) {
            logger.error("Failed to connect to ChurchTools: {}", e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // Bridge has no channels to command
    }

    @Override
    public void dispose() {
        if (discoveryRegistration != null) {
            discoveryRegistration.unregister();
            discoveryRegistration = null;
        }
        if (discoveryService != null) {
            discoveryService.stop();
        }
        super.dispose();
    }
    
    public ChurchToolsAPI getApi() {
        return api;
    }
    
    public int getRefreshInterval() {
        Configuration config = getThing().getConfiguration();
        int interval = 15;
        Object val = config.get("refreshInterval");
        if (val instanceof Number) {
            interval = ((Number) val).intValue();
        } else if (val instanceof String) {
            try {
                interval = Integer.parseInt((String) val);
            } catch (NumberFormatException e) {}
        }
        return interval > 0 ? interval : 15; // default to 15 if invalid or <= 0
    }
}
