package com.intenovation.mailcache.config;

import com.intenovation.appfw.config.*;
import com.intenovation.mailcache.CacheMode;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Configuration definition for MailCache CLI application.
 */
public class MailCacheConfig extends PersistentConfigurationBase {
    
    public MailCacheConfig() {
        super("mailcache-cli");
    }
    
    @Override
    public List<ConfigItem> getConfigItems() {
        List<ConfigItem> items = new ArrayList<>();
        

        // Cache settings
        items.add(new FilePathConfigItem("cache.directory", "Cache Directory", 
            new File(System.getProperty("user.home"), ".mailcache"),
            "Directory where mail cache will be stored"));
        

        return items;
    }
}