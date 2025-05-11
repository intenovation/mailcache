package com.intenovation.mailcache;

import com.intenovation.appfw.app.AbstractApplication;
import com.intenovation.appfw.app.Application;
import com.intenovation.appfw.app.CommandLineRunner;
import com.intenovation.appfw.config.ConfigItemTypeRegistry;
import com.intenovation.appfw.config.ConfigUtils;
import com.intenovation.appfw.config.ConfigurationDefinition;
import com.intenovation.appfw.task.BackgroundTask;
import com.intenovation.appfw.ui.UIServiceRegistry;
import com.intenovation.mailcache.config.CachedFolderType;
import com.intenovation.mailcache.config.CachedMessageType;
import com.intenovation.mailcache.config.MailCacheConfig;
import com.intenovation.mailcache.task.*;
import com.intenovation.passwordmanager.Password;
import com.intenovation.passwordmanager.PasswordManager;
import com.intenovation.passwordmanager.PasswordManagerApp;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Main CLI application for MailCache that integrates with PasswordManager.
 */
public class MailCacheApp extends AbstractApplication {
    private static final Logger LOGGER = Logger.getLogger(MailCacheApp.class.getName());

    private MailCacheManager mailCacheManager;
    private List<BackgroundTask> taskList;

    public MailCacheApp() {
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


            LOGGER.info("Using Password Manager for IMAP credentials");

            // Create MailCacheManager to integrate with password manager
            mailCacheManager =  MailCacheManager.getInstance( cacheDir);





        // Initialize task list for later use in showHelp()
        taskList = getTasks();

        super.initialize();
    }

    @Override
    public List<BackgroundTask> getTasks() {
        List<BackgroundTask> tasks = new ArrayList<>();

        // Create the message reading task
        ReadMessageTask readMessageTask = new ReadMessageTask();

        // Add the list folders task
        tasks.add(new ListFoldersTask());

        // Add the task that applies message reading to all messages in a folder
        tasks.add(new ApplyMessageTaskToFolder(readMessageTask));

        // Add a task to synchronize a folder
        tasks.add(new SynchronizeFolderTask());

        // Add a task to read messages
        tasks.add(readMessageTask);

        // Add search tasks that apply the read message task to search results
        tasks.add(new SearchBySenderTask(readMessageTask));
        tasks.add(new SearchBySubjectTask(readMessageTask));
        tasks.add(new SearchByYearTask(readMessageTask));

        // Add list stores task
        tasks.add(new ListStoresTask());
        tasks.add(new ChangeCacheModeTask());
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
            mailCacheManager.closeAllStores();
            LOGGER.info("MailCacheManager cleanup complete");
        }

        super.shutDown();
    }




}