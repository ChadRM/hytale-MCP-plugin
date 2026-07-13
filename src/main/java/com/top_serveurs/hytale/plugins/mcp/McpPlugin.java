package com.top_serveurs.hytale.plugins.mcp;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.logger.HytaleLogger;
import net.nitrado.hytale.plugins.webserver.WebServerPlugin;
import com.top_serveurs.hytale.plugins.mcp.auth.McpAuthManager;
import com.top_serveurs.hytale.plugins.mcp.config.McpConfig;
import com.top_serveurs.hytale.plugins.mcp.features.*;

import javax.annotation.Nonnull;
import java.io.File;

public class McpPlugin extends JavaPlugin {

    private static final PluginIdentifier WEBSERVER_PLUGIN_ID =
        new PluginIdentifier("Nitrado", "WebServer");

    private McpConfig config;
    private HytaleLogger logger;
    private WebServerPlugin webServerPlugin;
    private McpAuthManager authManager;
    private FeatureRegistry featureRegistry;
    private McpServlet mcpServlet;

    public McpPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        logger = getLogger();
        logger.atInfo().log("Initializing MCP plugin...");

        loadConfiguration();
        initializeWebServer();
        registerFeatures();
        registerEndpoints();

        logger.atInfo().log("MCP plugin initialized successfully");
    }

    @Override
    protected void shutdown() {
        logger.atInfo().log("Shutting down MCP plugin...");
        if (mcpServlet != null) {
            mcpServlet.shutdown();
        }
        if (webServerPlugin != null) {
            try {
                webServerPlugin.removeServlet(this, "/mcp");
            } catch (Exception e) {
                logger.atWarning().withCause(e).log("Failed to unregister MCP endpoint");
            }
        }
        logger.atInfo().log("MCP plugin shut down successfully");
    }

    private void loadConfiguration() {
        File configFile = new File(getDataFolder(), "config.json");
        config = McpConfig.load(configFile, logger);
        authManager = new McpAuthManager(config, logger);
    }

    private void initializeWebServer() {
        try {
            webServerPlugin = (WebServerPlugin) PluginManager.get().getPlugin(WEBSERVER_PLUGIN_ID);
            if (webServerPlugin == null) {
                logger.atSevere().log("WebServer plugin not found! MCP requires Nitrado:WebServer to be installed.");
            }
        } catch (Exception e) {
            logger.atSevere().withCause(e).log("Failed to initialize WebServer plugin");
        }
    }

    private void registerFeatures() {
        featureRegistry = new FeatureRegistry(logger);

        featureRegistry.registerFeature(new ListPlayersFeature(logger));
        featureRegistry.registerFeature(new GetPlayerPositionFeature(logger));
        featureRegistry.registerFeature(new ListBlocksFeature(logger));
        featureRegistry.registerFeature(new GetBlockFeature(logger));
        featureRegistry.registerFeature(new ScanRegionFeature(logger, config));
        featureRegistry.registerFeature(new GetHeightmapFeature(logger, config));
        featureRegistry.registerFeature(new ExecuteCommandFeature(logger, config));
        featureRegistry.registerFeature(new GiveItemFeature(logger, config));
        featureRegistry.registerFeature(new BroadcastMessageFeature(logger, config));
        featureRegistry.registerFeature(new SetBlockFeature(logger));
        featureRegistry.registerFeature(new BreakBlockFeature(logger));
        featureRegistry.registerFeature(new BreakBlocksBatchFeature(logger, config));
        featureRegistry.registerFeature(new SetBlocksBatchFeature(logger, config));
        featureRegistry.registerFeature(new FlattenTerrainFeature(logger, config));
        featureRegistry.registerFeature(new GetBuildingGuideFeature(logger, config));
        featureRegistry.registerFeature(new GetWorldInfoFeature(logger));
        featureRegistry.registerFeature(new GetServerInfoFeature(logger, config, getIdentifier()));
        featureRegistry.registerFeature(new SendChatMessageFeature(logger));
        featureRegistry.registerFeature(new GetLogsFeature(logger));

        logger.atInfo().log("Registered " + featureRegistry.toString() + " features");
    }

    private void registerEndpoints() {
        if (webServerPlugin == null) {
            return;
        }

        try {
            webServerPlugin.setAuthProviders(this);

            mcpServlet = new McpServlet(this);
            webServerPlugin.addServlet(this, "/mcp", mcpServlet);
            logger.atInfo().log("MCP endpoint registered at: /" + getIdentifier().getGroup() + "/" + getIdentifier().getName() + "/mcp");
        } catch (Exception e) {
            logger.atSevere().withCause(e).log("Failed to register MCP endpoint");
        }
    }

    public McpConfig getConfig() {
        return config;
    }

    public HytaleLogger getPluginLogger() {
        return logger;
    }

    public McpAuthManager getAuthManager() {
        return authManager;
    }

    public FeatureRegistry getFeatureRegistry() {
        return featureRegistry;
    }

    private File getDataFolder() {
        return new File("mods/MCP");
    }
}
