package com.intenovation.mailcache;

import com.intenovation.appfw.app.AbstractApplication;
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
import java.util.logging.Logger;

/**
 * Main CLI application for MailCache that integrates with PasswordManager.
 */
public class MailCacheCLI extends AbstractApplication {
    private static final Logger LOGGER = Logger.getLogger(MailCacheCLI.class.getName());
    
    private MailCacheManager mailCacheManager;
    
    public MailCacheCLI() {
        super("MailCache CLI");
    }
    
    @Override
    public ConfigurationDefinition getConfigurationDefinition() {
        return new MailCacheConfig();
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
        
        // Initialize mail cache stores if configured
        MailCacheConfig config = (MailCacheConfig) getConfigurationDefinition();
        
        // Get cache directory from config
        File cacheDir = ConfigUtils.getFile(config, "cache.directory", 
            new File(System.getProperty("user.home"), ".mailcache"));
        
        // Try to find PasswordManagerApp from TaskRegistry (it should be registered if running in integrated mode)
        PasswordManagerApp passwordManagerApp = null;
        for (String appName : com.intenovation.appfw.task.TaskRegistry.getInstance().getRegisteredApplications()) {
            if (appName.equals("Password Manager")) {
                // This is a bit of a hack - we need a way to get the actual app instance
                // In a real implementation, we might need to enhance AppFw to support this
                LOGGER.info("Password Manager found in registry, but instance retrieval not yet implemented");
                break;
            }
        }
        
        // If we're running in integrated mode, try to get password manager from system property
        String integratedMode = System.getProperty("mailcache.integrated");
        if ("true".equals(integratedMode)) {
            passwordManagerApp = (PasswordManagerApp) System.getProperties().get("password.manager.instance");
        }
        
        // If we have a password manager, use it to initialize stores
        if (passwordManagerApp != null) {
            LOGGER.info("Using Password Manager for IMAP credentials");
            
            // Create MailCacheManager to integrate with password manager
            mailCacheManager = new MailCacheManager(passwordManagerApp, cacheDir);
            
            // Initialize all stores from password manager
            mailCacheManager.initializeAllStores();
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
                LOGGER.severe("Error initializing mail cache: " + e.getMessage());
            }
        }
        
        super.initialize();
    }
    
    @Override
    public List<BackgroundTask> getTasks() {
        List<BackgroundTask> tasks = new ArrayList<>();
        
        // Create the message reading task
        ReadMessage readMessageTask = new ReadMessage(null);
        
        // Add the task that applies message reading to all messages in a folder
        tasks.add(new ApplyMessageTaskToFolder(readMessageTask));
        
        // Add a task to synchronize a folder
        tasks.add(new SynchronizeFolderTask());
        
        // Add a task to archive old messages
        //tasks.add(new ArchiveOldMessagesTask());
        
        return tasks;
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
            // This will be handled by MailCache.closeAllStores()
            LOGGER.info("MailCacheManager cleanup handled by MailCache");
        }
        
        super.shutDown();
    }
    
    @Override
    public int execute(String[] args) {
        // If no specific command is given, show help
        if (args.length == 0) {
            System.out.println("MailCache CLI - Available commands:");
            System.out.println("  --config         Edit configuration interactively");
            System.out.println("  --list-tasks     List available tasks");
            System.out.println("  --task <name>    Run a specific task");
            System.out.println("  --help           Show help");
            System.out.println();
            System.out.println("Example: java -jar app.jar --app \"MailCache CLI\" --task apply-to-folder");
            System.out.println();
            System.out.println("When running with Password Manager integration:");
            System.out.println("  1. Add IMAP accounts using: --app \"Password Manager\" add imap server.com user");
            System.out.println("  2. Use MailCache tasks: --app \"MailCache CLI\" --task sync-folder");
            
            return 0;
        }
        
        // Process other commands
        return 0;
    }
    
    public static void main(String[] args) {
        CommandLineRunner.main(args, new MailCacheCLI());
    }
}