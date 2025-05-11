package com.intenovation.mailcache.task;

import com.intenovation.appfw.config.ConfigItemType;
import com.intenovation.appfw.config.StandardConfigItemTypes;
import com.intenovation.appfw.task.BackgroundTask;
import com.intenovation.appfw.task.ProgressStatusCallback;
import com.intenovation.mailcache.CacheMode;
import com.intenovation.mailcache.MailCacheManager;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Task to change the cache mode of a mail store using the password manager API.
 */
public class ChangeCacheModeTask extends BackgroundTask {
    private static final Logger LOGGER = Logger.getLogger(ChangeCacheModeTask.class.getName());

    public ChangeCacheModeTask() {
        super("change-cache-mode", "Change the cache mode of a mail store");
    }

    @Override
    public ConfigItemType getParameterType() {
        return StandardConfigItemTypes.TEXT;
    }

    @Override
    public String getParameterName() {
        return "Details";
    }

    @Override
    public String getParameterDescription() {
        return "Format: <username> <mode> - Mode must be one of: OFFLINE, ACCELERATED, ONLINE, REFRESH, DESTRUCTIVE";
    }

    @Override
    public String execute(ProgressStatusCallback callback, Object parameter) {
        if (!(parameter instanceof String)) {
            return "Error: Parameter must be a string";
        }

        String param = (String) parameter;
        String[] parts = param.split("\\s+", 2);
        if (parts.length != 2) {
            return "Error: Invalid format. Expected '<username> <mode>'";
        }

        String username = parts[0];
        String modeStr = parts[1].toUpperCase();

        // Validate the cache mode
        CacheMode mode;
        try {
            mode = CacheMode.valueOf(modeStr);
        } catch (IllegalArgumentException e) {
            return "Error: Invalid cache mode '" + modeStr + "'. Valid modes are: OFFLINE, ACCELERATED, ONLINE, REFRESH, DESTRUCTIVE";
        }

        callback.update(0, "Changing cache mode for " + username + " to " + mode);

        try {
            // Get the MailCacheManager from the application
            MailCacheManager mailCacheManager = MailCacheManager.getInstance();
            if (mailCacheManager == null) {
                return "Error: MailCacheManager is not available";
            }

            callback.update(50, "Updating cache mode...");

            // Update the cache mode using the password manager API
            boolean success = mailCacheManager.updateCacheMode(username, mode);

            if (success) {
                callback.update(100, "Cache mode updated successfully");
                return "Successfully changed cache mode for " + username + " to " + mode;
            } else {
                callback.update(100, "Failed to update cache mode");
                return "Failed to change cache mode for " + username + ". User may not exist or there was an error updating the password record.";
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error changing cache mode", e);
            return "Error changing cache mode: " + e.getMessage();
        }
    }
}