package com.zin.delamain.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;

/**
 * Authentication Configuration Manager.
 *
 * Handles generation, storage, and validation of API authentication tokens.
 * Tokens are stored in a secure properties file and validated on each request.
 *
 * Supports file-mount mode via DELAMAIN_AUTH_TOKEN_FILE environment variable,
 * which is the recommended pattern for Docker secrets and container deployments.
 */
public class AuthConfig {
    private static final Logger logger = LoggerFactory.getLogger(AuthConfig.class);

    private static final String CONFIG_DIR = System.getProperty("user.home") + File.separator + ".delamain";
    private static final String AUTH_CONFIG_FILE = CONFIG_DIR + File.separator + "auth.properties";
    private static final String TOKEN_KEY = "auth.token";
    private static final String ENABLED_KEY = "auth.enabled";
    private static final int TOKEN_LENGTH = 32; // 32 bytes = 256 bits

    private String authToken;
    private boolean authEnabled;

    // Environment variable overrides (used in Docker deployments)
    private String envAuthToken = null;
    private Boolean envAuthEnabled = null;

    // File-mount mode: token sourced from an external secret file
    private boolean externallyManaged = false;
    private String tokenFilePath = null;

    /**
     * Initializes authentication configuration with a fixed token from CLI.
     * Auth is always enabled when a token is provided via CLI.
     *
     * @param cliToken token provided via --auth-token CLI argument (required)
     */
    public AuthConfig(String cliToken) {
        this.authToken = cliToken;
        this.authEnabled = (cliToken != null && !cliToken.isEmpty());
        logger.info("Authentication configured from CLI token");
    }

    /**
     * Initializes authentication configuration with optional environment overrides.
     */
    public AuthConfig(String envToken, Boolean envEnabled) {
        this(envToken, envEnabled, null);
    }

    /**
     * Initializes authentication configuration with optional environment overrides
     * and optional file-mount path.
     *
     * Priority (highest to lowest):
     *   1. DELAMAIN_AUTH_TOKEN_FILE (file-mount / Docker secrets)
     *   2. DELAMAIN_AUTH_TOKEN (env var)
     *   3. Persisted ~/.delamain/auth.properties
     *   4. Generated random token
     */
    public AuthConfig(String envToken, Boolean envEnabled, String tokenFilePath) {
        this.envAuthToken = envToken;
        this.envAuthEnabled = envEnabled;
        this.tokenFilePath = tokenFilePath;
        ensureConfigDirectory();
        loadOrGenerateToken();
        applyEnvironmentOverrides();
    }

    private void applyEnvironmentOverrides() {
        // Priority 1: file-mount token (Docker secrets pattern)
        if (tokenFilePath != null && !tokenFilePath.isEmpty()) {
            String fileToken = loadTokenFromFile(tokenFilePath);
            authToken = fileToken;
            externallyManaged = true;
            logger.info("Using authentication token from file: " + tokenFilePath);
            if (envAuthEnabled != null) {
                authEnabled = envAuthEnabled;
            }
            return;
        }
        // Priority 2: env var token
        if (envAuthToken != null && !envAuthToken.isEmpty()) {
            authToken = envAuthToken;
            logger.info("Using authentication token from environment variable");
        }
        if (envAuthEnabled != null) {
            authEnabled = envAuthEnabled;
            logger.info("Authentication " + (authEnabled ? "enabled" : "disabled") + " via environment variable");
        }
    }

    private String loadTokenFromFile(String filePath) {
        try {
            byte[] bytes = Files.readAllBytes(Paths.get(filePath));
            String token = new String(bytes, StandardCharsets.UTF_8).trim();
            if (token.isEmpty()) {
                String msg = "DELAMAIN_AUTH_TOKEN_FILE points to an empty file: " + filePath;
                logger.error(msg);
                throw new RuntimeException(msg);
            }
            return token;
        } catch (IOException e) {
            String msg = "DELAMAIN_AUTH_TOKEN_FILE is set but file cannot be read: " + filePath
                       + " — " + e.getMessage();
            logger.error(msg);
            throw new RuntimeException(msg, e);
        }
    }

    public boolean isExternallyManaged() {
        return externallyManaged;
    }

    private void ensureConfigDirectory() {
        File configDir = new File(CONFIG_DIR);
        if (!configDir.exists()) {
            if (configDir.mkdirs()) {
                logger.info("Created delamain config directory: " + CONFIG_DIR);
            } else {
                logger.warn("Failed to create config directory: " + CONFIG_DIR);
            }
        }
    }

    private void loadOrGenerateToken() {
        File configFile = new File(AUTH_CONFIG_FILE);
        if (configFile.exists()) {
            loadToken();
        } else {
            generateAndSaveToken();
        }
    }

    private void loadToken() {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(AUTH_CONFIG_FILE)) {
            props.load(fis);
            authToken = props.getProperty(TOKEN_KEY);
            authEnabled = Boolean.parseBoolean(props.getProperty(ENABLED_KEY, "false"));
            if (authToken == null || authToken.isEmpty()) {
                logger.warn("Invalid token in config file, regenerating...");
                generateAndSaveToken();
            } else {
                logger.info("Loaded authentication token from config");
            }
        } catch (IOException e) {
            logger.error("Failed to load auth config: " + e.getMessage(), e);
            generateAndSaveToken();
        }
    }

    private void generateAndSaveToken() {
        authToken = generateSecureToken();
        authEnabled = false;
        saveToken();
    }

    private String generateSecureToken() {
        SecureRandom random = new SecureRandom();
        byte[] tokenBytes = new byte[TOKEN_LENGTH];
        random.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    private void saveToken() {
        if (externallyManaged) {
            return;
        }
        Properties props = new Properties();
        props.setProperty(TOKEN_KEY, authToken);
        props.setProperty(ENABLED_KEY, String.valueOf(authEnabled));
        try (FileOutputStream fos = new FileOutputStream(AUTH_CONFIG_FILE)) {
            props.store(fos, "JADX MCP Core Authentication Configuration");
            logger.info("Authentication token saved to: " + AUTH_CONFIG_FILE);
        } catch (IOException e) {
            logger.error("Failed to save auth config: " + e.getMessage(), e);
            return;
        }
        restrictFilePermissions(Paths.get(AUTH_CONFIG_FILE));
    }

    /**
     * Restricts a file to owner-only read/write (0600). Used for auth.properties, which holds a
     * bearer token in plaintext — group/world-readable permissions would leak it to other local
     * users. Silently skipped on non-POSIX filesystems (e.g. Windows), where this class of
     * exposure doesn't apply the same way.
     *
     * <p>Package-private + static (instead of folded into {@link #saveToken()}) so it can be unit
     * tested against a throwaway temp file, without touching the real {@code ~/.delamain}
     * config directory that {@link #AUTH_CONFIG_FILE} points at.
     */
    static void restrictFilePermissions(java.nio.file.Path path) {
        try {
            if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(path,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            }
        } catch (Exception e) {
            logger.warn("Failed to restrict permissions on auth config file: " + e.getMessage());
        }
    }

    /**
     * Validates an incoming request token against the stored token.
     *
     * @param requestToken the token from the HTTP request (without "Bearer " prefix)
     * @return true if authentication is disabled or token matches
     */
    public boolean validateToken(String requestToken) {
        if (!authEnabled) {
            return true;
        }
        if (requestToken == null || requestToken.isEmpty()) {
            logger.warn("Request missing authentication token");
            return false;
        }
        return constantTimeEquals(authToken, requestToken);
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        int result = aBytes.length ^ bBytes.length;
        int minLen = Math.min(aBytes.length, bBytes.length);
        for (int i = 0; i < minLen; i++) {
            result |= aBytes[i] ^ bBytes[i];
        }
        return result == 0;
    }

    public String getAuthToken() {
        return authToken;
    }

    public boolean isAuthEnabled() {
        return authEnabled;
    }

    public void setAuthEnabled(boolean enabled) {
        this.authEnabled = enabled;
        saveToken();
        logger.info("Authentication " + (enabled ? "enabled" : "disabled"));
    }

    public boolean regenerateToken() {
        if (externallyManaged) {
            logger.warn("Token sourced from DELAMAIN_AUTH_TOKEN_FILE; rotation disabled");
            return false;
        }
        authToken = generateSecureToken();
        saveToken();
        logger.info("Authentication token regenerated");
        return true;
    }

    public boolean setAuthToken(String token) {
        if (externallyManaged) {
            logger.warn("Token sourced from DELAMAIN_AUTH_TOKEN_FILE; rotation disabled");
            return false;
        }
        if (token != null && !token.isEmpty()) {
            this.authToken = token;
            saveToken();
            logger.info("Authentication token updated manually");
            return true;
        }
        return false;
    }

    public String getConfigFilePath() {
        return AUTH_CONFIG_FILE;
    }
}
