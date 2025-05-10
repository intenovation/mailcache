package com.intenovation.mailcache.task;

import com.intenovation.appfw.task.BackgroundTask;
import com.intenovation.appfw.task.ProgressStatusCallback;
import com.intenovation.appfw.ui.UIServiceRegistry;
import com.intenovation.mailcache.CachedStore;
import com.intenovation.mailcache.MailCache;

import java.util.Collection;
import java.util.logging.Logger;

/**
 * Task that lists all available mail stores.
 */
public class ListStoresTask extends BackgroundTask {
    private static final Logger LOGGER = Logger.getLogger(ListStoresTask.class.getName());

    public ListStoresTask() {
        super("list-stores", "Lists all available mail stores");
    }

    @Override
    public boolean hasParameter(){
        return false;
    }

    @Override
    public String execute(ProgressStatusCallback callback, Object parameter) throws InterruptedException {
        callback.update(0, "Getting all mail stores...");

        Collection<CachedStore> allStores = MailCache.getAllStores();

        if (allStores.isEmpty()) {
            LOGGER.warning("No mail stores found! Check if stores were properly initialized.");
            callback.update(100, "No mail stores found!");
            UIServiceRegistry.getUIService().showWarning("No Stores Found",
                    "No mail stores were found. Please check your configuration and ensure stores are initialized.");
            return "No mail stores found";
        }

        StringBuilder result = new StringBuilder();
        int count = 0;

        callback.update(50, "Processing " + allStores.size() + " stores...");

        for (CachedStore store : allStores) {
            count++;
            String username = store.getUsername();
            String mode = store.getMode().toString();
            boolean connected = store.isConnected();

            result.append(String.format("Store #%d: %s (Mode: %s, Connected: %s)\n",
                    count, username, mode, connected ? "Yes" : "No"));

            callback.update(50 + (count * 50 / allStores.size()),
                    "Processing store: " + username);
        }

        LOGGER.info("Found " + count + " mail stores");

        callback.update(100, "Completed. Found " + count + " stores.");

        if (count > 0) {
            UIServiceRegistry.getUIService().showInfo("Mail Stores",
                    "Found " + count + " mail stores:\n\n" + result.toString());
        }

        return "Stores: " + count;
    }
}