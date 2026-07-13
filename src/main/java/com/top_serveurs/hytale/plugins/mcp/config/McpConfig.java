package com.top_serveurs.hytale.plugins.mcp.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class McpConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    
    private AuthConfig auth;
    private FeaturesConfig features;

    public McpConfig() {
        this.auth = new AuthConfig();
        this.features = new FeaturesConfig();
        
        // Auto-generate secure tokens if not present
        if (auth.getAdminTokens().isEmpty()) {
            String adminToken = generateSecureToken();
            auth.getAdminTokens().add(adminToken);
        }
        
        if (auth.getPlayerTokens().isEmpty()) {
            String playerToken = generateSecureToken();
            auth.getPlayerTokens().add(playerToken);
        }
    }
    
    private static String generateSecureToken() {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        return "mcp_" + Base64.getEncoder().withoutPadding().encodeToString(randomBytes);
    }

    public static McpConfig load(File configFile, HytaleLogger logger) {
        if (!configFile.exists()) {
            logger.atInfo().log("Config file not found, creating default config at: " + configFile.getAbsolutePath());
            McpConfig config = new McpConfig();
            config.save(configFile, logger);
            return config;
        }

        try (Reader reader = Files.newBufferedReader(configFile.toPath())) {
            McpConfig config = GSON.fromJson(reader, McpConfig.class);
            
            // Check if tokens are empty and generate them
            if (config.auth.getAdminTokens().isEmpty()) {
                String adminToken = generateSecureToken();
                config.auth.getAdminTokens().add(adminToken);
                logger.atWarning().log("No admin tokens found, generating new one: " + adminToken);
                config.save(configFile, logger);
            }
            
            if (config.auth.getPlayerTokens().isEmpty()) {
                String playerToken = generateSecureToken();
                config.auth.getPlayerTokens().add(playerToken);
                logger.atWarning().log("No player tokens found, generating new one: " + playerToken);
                config.save(configFile, logger);
            }
            
            return config;
        } catch (IOException e) {
            logger.atSevere().withCause(e).log("Failed to load config file, using defaults");
            return new McpConfig();
        }
    }

    public void save(File configFile, HytaleLogger logger) {
        try {
            File parentDir = configFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            try (Writer writer = Files.newBufferedWriter(configFile.toPath())) {
                GSON.toJson(this, writer);
            }
            logger.atInfo().log("Config saved to: " + configFile.getAbsolutePath());
        } catch (IOException e) {
            logger.atSevere().withCause(e).log("Failed to save config file");
        }
    }

    public AuthConfig getAuth() {
        return auth;
    }

    public FeaturesConfig getFeatures() {
        return features;
    }

    public static class AuthConfig {
        private Set<String> adminTokens = new HashSet<>();
        private Set<String> playerTokens = new HashSet<>();
        private boolean enabled = true;

        public Set<String> getAdminTokens() {
            return adminTokens;
        }

        public void setAdminTokens(Set<String> adminTokens) {
            this.adminTokens = adminTokens;
        }

        public Set<String> getPlayerTokens() {
            return playerTokens;
        }

        public void setPlayerTokens(Set<String> playerTokens) {
            this.playerTokens = playerTokens;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class FeaturesConfig {
        private FeaturePermissions players = new FeaturePermissions();
        private FeaturePermissions admins = new FeaturePermissions();
        private int maxBlocksBatch = 1000;

        public FeaturePermissions getPlayers() {
            return players;
        }

        public void setPlayers(FeaturePermissions players) {
            this.players = players;
        }

        public FeaturePermissions getAdmins() {
            return admins;
        }

        public void setAdmins(FeaturePermissions admins) {
            this.admins = admins;
        }

        public int getMaxBlocksBatch() {
            return maxBlocksBatch;
        }

        public void setMaxBlocksBatch(int maxBlocksBatch) {
            this.maxBlocksBatch = maxBlocksBatch;
        }
    }

    public static class FeaturePermissions {
        private boolean listPlayers = false;
        private boolean executeCommand = false;
        private boolean broadcastMessage = false;
        private boolean setBlock = false;
        private boolean getPlayerPosition = false;
        private boolean getLogs = false;
        private boolean sendChatMessage = false;
        private boolean getBlockTypes = false;
        private boolean getWorldInfo = false;
        private boolean getServerInfo = false;
        private boolean listBlocks = false;
        private boolean getBlock = false;

        public boolean canListPlayers() {
            return listPlayers;
        }

        public void setListPlayers(boolean listPlayers) {
            this.listPlayers = listPlayers;
        }

        public boolean canExecuteCommand() {
            return executeCommand;
        }

        public void setExecuteCommand(boolean executeCommand) {
            this.executeCommand = executeCommand;
        }

        public boolean canBroadcastMessage() {
            return broadcastMessage;
        }

        public void setBroadcastMessage(boolean broadcastMessage) {
            this.broadcastMessage = broadcastMessage;
        }

        public boolean canSetBlock() {
            return setBlock;
        }

        public void setSetBlock(boolean setBlock) {
            this.setBlock = setBlock;
        }

        public boolean canGetPlayerPosition() {
            return getPlayerPosition;
        }

        public void setGetPlayerPosition(boolean getPlayerPosition) {
            this.getPlayerPosition = getPlayerPosition;
        }

        public boolean canGetLogs() {
            return getLogs;
        }

        public void setGetLogs(boolean getLogs) {
            this.getLogs = getLogs;
        }

        public boolean canSendChatMessage() {
            return sendChatMessage;
        }

        public void setSendChatMessage(boolean sendChatMessage) {
            this.sendChatMessage = sendChatMessage;
        }

        public boolean canGetBlockTypes() {
            return getBlockTypes;
        }

        public void setGetBlockTypes(boolean getBlockTypes) {
            this.getBlockTypes = getBlockTypes;
        }

        public boolean canGetWorldInfo() {
            return getWorldInfo;
        }

        public void setGetWorldInfo(boolean getWorldInfo) {
            this.getWorldInfo = getWorldInfo;
        }

        public boolean canGetServerInfo() {
            return getServerInfo;
        }

        public void setGetServerInfo(boolean getServerInfo) {
            this.getServerInfo = getServerInfo;
        }

        public boolean canListBlocks() {
            return listBlocks;
        }

        public void setListBlocks(boolean listBlocks) {
            this.listBlocks = listBlocks;
        }

        public boolean canGetBlock() {
            return getBlock;
        }

        public void setGetBlock(boolean getBlock) {
            this.getBlock = getBlock;
        }
    }
}
