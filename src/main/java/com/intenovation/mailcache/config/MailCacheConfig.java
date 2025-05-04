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
        
        // Mail server settings
        items.add(new TextConfigItem("imap.host", "IMAP Server Host", "", 
            "IMAP server hostname (e.g., imap.gmail.com)"));
        items.add(new NumberConfigItem("imap.port", "IMAP Server Port", 993, 
            "IMAP server port (typically 993 for SSL, 143 for non-SSL)"));
        items.add(new TextConfigItem("imap.username", "Username", "", 
            "Your email address or username"));
        items.add(new PasswordConfigItem("imap.password", "Password", "", 
            "Your email password"));
        items.add(new CheckboxConfigItem("imap.ssl", "Use SSL", true, 
            "Use SSL/TLS for secure connection"));
        
        // Cache settings
        items.add(new FilePathConfigItem("cache.directory", "Cache Directory", 
            new File(System.getProperty("user.home"), ".mailcache"),
            "Directory where mail cache will be stored"));
        
        // Create an enum config for cache mode
        List<String> cacheModes = new ArrayList<>();
        for (CacheMode mode : CacheMode.values()) {
            cacheModes.add(mode.name());
        }
        items.add(new EnumConfigItem("cache.mode", "Cache Mode", "ACCELERATED", 
            cacheModes, "Operation mode for the mail cache"));
        
        return items;
    }
}