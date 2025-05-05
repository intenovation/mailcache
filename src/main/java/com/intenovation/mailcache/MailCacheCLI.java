package com.intenovation.mailcache;

import com.intenovation.appfw.app.AbstractApplication;
import com.intenovation.appfw.app.Application;
import com.intenovation.appfw.app.CommandLineRunner;
import com.intenovation.appfw.config.*;
import com.intenovation.appfw.task.BackgroundTask;
import com.intenovation.mailcache.*;
import com.intenovation.mailcache.config.*;
import com.intenovation.mailcache.task.*;
import com.intenovation.passwordmanager.PasswordManagerApp;
import com.intenovation.passwordmanager.Password;
import com.intenovation.passwordmanager.PasswordType;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.logging.Logger;

/**
 * Main CLI application for MailCache that integrates with PasswordManager.
 */
public class MailCacheCLI extends AbstractApplication {
    private static final Logger LOGGER = Logger.getLogger(MailCacheCLI.class.getName());

    private MailCacheManager mailCacheManager;
    private PasswordManagerApp passwordManagerApp;
    private List<BackgroundTask> taskList;

    public MailCacheCLI() {
        super("MailCache CLI");
    }

    @Override
    public ConfigurationDefinition getConfigurationDefinition() {
        return new MailCacheConfig();
    }

    @Override
    public void setApplicationReference(String appName, Application app) {
        if ("Password Manager".equals(appName) && app instanceof PasswordManagerApp) {
            this.passwordManagerApp = (PasswordManagerApp) app;
            LOGGER.info("Password Manager reference set in MailCache CLI");
        }
    }

    @Override
    public void initialize() {
        // Register custom configuration types for mailcache tasks
        ConfigItemTypeRegistry registry = ConfigItemTypeRegistry.getInstance();

        // Register types if not already registered
        if (!registry.isTypeRegistered(CachedFolderType.TYPE_ID)) {
            registry.registerType(new CachedFolderType());
        }
        if (!registry.isTypeRegistered(CachedMessageType.TYPE_ID)) {
            registry.registerType(new CachedMessageType());
        }

        // Try to find Password Manager if we don't have a reference yet
        if (passwordManagerApp == null) {
            // Check if available from system properties (set by MailCacheIntegrated)
            Object instance = System.getProperties().get("password.manager.instance");
            if (instance instanceof PasswordManagerApp) {
                passwordManagerApp = (PasswordManagerApp) instance;
                LOGGER.info("Found Password Manager instance from system properties");
            }
        }

        // Initialize mail cache stores if configured
        MailCacheConfig config = (MailCacheConfig) getConfigurationDefinition();

        // Get cache directory from config
        File cacheDir = ConfigUtils.getFile(config, "cache.directory",
                new File(System.getProperty("user.home"), ".mailcache"));

        // If we have a password manager reference, use it
        if (passwordManagerApp != null) {
            LOGGER.info("Using Password Manager for IMAP credentials");

            // Create MailCacheManager to integrate with password manager
            mailCacheManager = new MailCacheManager(passwordManagerApp, cacheDir);

            // Initialize all stores from password manager
            mailCacheManager.initializeAllStores();

            // Update our configuration to reflect the first IMAP account from password manager
            // This makes the configuration display more accurate
            List<Password> imapPasswords = PasswordManagerApp.getImapPasswords();
            if (!imapPasswords.isEmpty()) {
                Password firstImapPassword = imapPasswords.get(0);

                // Create a map of configuration values to update
                Map<String, Object> configValues = new HashMap<>(config.getCurrentValues());

                // Update configuration values from password manager
                configValues.put("imap.host", firstImapPassword.getUrl());
                configValues.put("imap.username", firstImapPassword.getUsername());

                // Get port from custom properties
                String portStr = firstImapPassword.getProperty(MailCacheManager.PROP_PORT);
                if (portStr != null && !portStr.isEmpty()) {
                    try {
                        int port = Integer.parseInt(portStr);
                        configValues.put("imap.port", port);
                    } catch (NumberFormatException e) {
                        // Keep default port
                    }
                }

                // Get SSL setting from custom properties
                String sslStr = firstImapPassword.getProperty(MailCacheManager.PROP_SSL);
                if (sslStr != null && !sslStr.isEmpty()) {
                    configValues.put("imap.ssl", Boolean.parseBoolean(sslStr));
                }

                // Get cache mode from custom properties
                CacheMode storedMode = firstImapPassword.getEnumProperty(
                        MailCacheManager.PROP_CACHE_MODE, CacheMode.class);
                if (storedMode != null) {
                    configValues.put("cache.mode", storedMode.name());
                }

                // Note: We don't update the password field for security reasons
                // It's already available through the password manager

                // Apply the updated configuration
                config.applyConfiguration(configValues);

                LOGGER.info("Updated MailCache configuration from Password Manager settings");
            }
        } else {
            // Fall back to traditional configuration-based initialization
            LOGGER.info("Password Manager not available, using configuration for IMAP credentials");

            try {
                String imapHost = ConfigUtils.getString(config, "imap.host", "");
                int imapPort = ConfigUtils.getInt(config, "imap.port", 993);
                String username = ConfigUtils.getString(config, "imap.username", "");
                String password = ConfigUtils.getString(config, "imap.password", "");
                boolean useSSL = ConfigUtils.getBoolean(config, "imap.ssl", true);
                String cacheModeStr = ConfigUtils.getString(config, "cache.mode", "ACCELERATED");

                CacheMode cacheMode = CacheMode.valueOf(cacheModeStr);

                // Open mail store if configured
                if (!imapHost.isEmpty() && !username.isEmpty() && !password.isEmpty()) {
                    LOGGER.info("Opening mail store for user: " + username);
                    CachedStore store = MailCache.openStore(
                            cacheDir, cacheMode, imapHost, imapPort,
                            username, password, useSSL
                    );

                    if (store != null && store.isConnected()) {
                        LOGGER.info("Successfully connected to mail store");
                    } else {
                        LOGGER.warning("Failed to connect to mail store");
                    }
                } else {
                    LOGGER.warning("Mail store not configured - some tasks may not work");
                }

            } catch (Exception e) {
                LOGGER.warning("Error initializing mail cache: " + e.getMessage());
            }
        }

        // Initialize task list for later use in showHelp()
        taskList = getTasks();

        super.initialize();
    }

    @Override
    public List<BackgroundTask> getTasks() {
        List<BackgroundTask> tasks = new ArrayList<>();

        // Create the message reading task
        ReadMessage readMessageTask = new ReadMessage();

        // Add the list folders task
        tasks.add(new ListFoldersTask());

        // Add the task that applies message reading to all messages in a folder
        tasks.add(new ApplyMessageTaskToFolder(readMessageTask));

        // Add a task to synchronize a folder
        tasks.add(new SynchronizeFolderTask());

        // Add a task to read messages
        tasks.add(readMessageTask);

        // Add a task to list stores
        if (passwordManagerApp != null) {
            tasks.add(new ListStores(passwordManagerApp.getUIService()));
        } else {
            tasks.add(new ListStores(new com.intenovation.appfw.ui.CLIUIService()));
        }

        return tasks;
    }

    /**
     * Show available tasks in the help message
     */
    private void showAvailableTasks() {
        if (taskList == null) {
            taskList = getTasks();
        }

        System.out.println("\nAvailable tasks:");
        for (BackgroundTask task : taskList) {
            System.out.println("  - " + task.getName() +
                    (task.getDescription() != null ? ": " + task.getDescription() : ""));
        }
    }

    @Override
    public void shutDown() {
        // Close all mail stores using MailCache
        try {
            MailCache.closeAllStores();
            LOGGER.info("Closed all mail stores");
        } catch (Exception e) {
            LOGGER.warning("Error closing mail stores: " + e.getMessage());
        }

        // If we have a mailCacheManager, clean it up too
        if (mailCacheManager != null) {
            mailCacheManager.closeAllStores();
            LOGGER.info("MailCacheManager cleanup complete");
        }

        super.shutDown();
    }

    @Override
    public int execute(String[] args) {
        // If no specific command is given, show help
        if (args.length == 0) {
            showHelp();
            return 0;
        }

        // Process other commands
        return 0;
    }

    /**
     * Show the help message with all available commands and tasks
     */
    private void showHelp() {
        System.out.println("MailCache CLI - Available commands:");
        System.out.println("  --config         Edit configuration interactively");
        System.out.println("  --show-config    Show current configuration");
        System.out.println("  --list-tasks     List available tasks");
        System.out.println("  --task <name>    Run a specific task");
        System.out.println("  --repl           Start interactive REPL mode");
        System.out.println("  --help           Show help");

        // Show available tasks
        showAvailableTasks();

        System.out.println();

        if (passwordManagerApp != null) {
            System.out.println("Password Manager integration is active!");
            System.out.println("IMAP credentials are loaded from Password Manager.");
            System.out.println();
            System.out.println("To add a new IMAP account:");
            System.out.println("  mailcache.sh --app \"Password Manager\" add imap server.com user");
            System.out.println();
            System.out.println("To sync a folder:");
            System.out.println("  mailcache.sh --app \"MailCache CLI\" --task sync-folder");
            System.out.println();
            System.out.println("To switch between applications in REPL mode:");
            System.out.println("  mailcache.sh --repl");
            System.out.println("  Then use 'app \"Password Manager\"' to switch to Password Manager");
            System.out.println("  Or 'app \"MailCache CLI\"' to switch back to MailCache");
        } else {
            System.out.println("Running in standalone mode (no Password Manager integration)");
            System.out.println("Configure IMAP credentials using: --config");
        }
    }

    public static void main(String[] args) {
        CommandLineRunner.main(args, new MailCacheCLI());
    }
}