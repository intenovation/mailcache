package com.intenovation.mailcache.task;

import com.intenovation.appfw.config.ConfigItemType;
import com.intenovation.appfw.config.StandardConfigItemTypes;
import com.intenovation.appfw.task.BackgroundTask;
import com.intenovation.appfw.task.ProgressStatusCallback;
import com.intenovation.mailcache.CachedFolder;
import com.intenovation.mailcache.CachedMessage;
import com.intenovation.mailcache.CachedStore;
import com.intenovation.mailcache.MailCache;

import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.search.SearchTerm;
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Abstract base class for search-related tasks.
 */
public abstract class AbstractSearchTask extends BackgroundTask {
    private static final Logger LOGGER = Logger.getLogger(AbstractSearchTask.class.getName());
    
    public AbstractSearchTask(String name, String description) {
        super(name, description);
    }
    
    @Override
    public ConfigItemType getParameterType() {
        return StandardConfigItemTypes.TEXT;
    }
    
    @Override
    public String execute(ProgressStatusCallback callback, Object parameter) 
            throws InterruptedException {
        
        if (!(parameter instanceof String)) {
            return "Invalid parameter: Expected a string search term";
        }
        
        String searchTerm = (String) parameter;
        if (searchTerm.isEmpty()) {
            return "Search term cannot be empty";
        }
        
        callback.update(0, "Preparing to search for: " + searchTerm);
        
        try {
            // Get the first available store
            Collection<CachedStore> stores = MailCache.getAllStores();
            if (stores.isEmpty()) {
                return "No mail stores available. Please configure and connect to a mail store first.";
            }
            
            CachedStore store = stores.iterator().next();
            LOGGER.info("Using store: " + store.getUsername());
            
            // Get the INBOX folder
            CachedFolder inbox = store.getFolder("INBOX");
            if (!inbox.exists()) {
                return "INBOX folder does not exist";
            }
            
            boolean wasOpen = inbox.isOpen();
            if (!wasOpen) {
                inbox.open(Folder.READ_ONLY);
            }
            
            try {
                // Create the search term
                SearchTerm javaxSearchTerm = createSearchTerm(searchTerm);
                
                // Perform the search
                callback.update(20, "Executing search query...");
                Message[] messages = inbox.search(javaxSearchTerm);
                
                if (messages == null || messages.length == 0) {
                    return "No messages found matching search criteria: " + searchTerm;
                }
                
                // Process search results
                callback.update(50, "Found " + messages.length + " messages. Processing...");
                
                return processSearchResults(callback, messages, searchTerm);
                
            } finally {
                // Close the folder if we opened it
                if (!wasOpen && inbox.isOpen()) {
                    inbox.close(false);
                }
            }
            
        } catch (MessagingException e) {
            LOGGER.log(Level.WARNING, "Error searching for messages: " + e.getMessage(), e);
            return "Error searching for messages: " + e.getMessage();
        }
    }
    
    /**
     * Create a search term for the given search string.
     * Subclasses must implement this to provide specific search criteria.
     * 
     * @param searchString The search string from the user
     * @return A JavaMail SearchTerm object
     */
    protected abstract SearchTerm createSearchTerm(String searchString);
    
    /**
     * Process the search results.
     * Subclasses can override this to customize the handling of results.
     * 
     * @param callback Progress callback
     * @param messages The messages found by the search
     * @param searchTerm The original search term
     * @return Result message
     * @throws InterruptedException if the task is interrupted
     * @throws MessagingException if there is a messaging error
     */
    protected String processSearchResults(ProgressStatusCallback callback, Message[] messages, String searchTerm)
            throws InterruptedException, MessagingException {
        
        StringBuilder result = new StringBuilder();
        result.append("Found ").append(messages.length).append(" messages matching criteria: ").append(searchTerm).append("\n\n");
        
        int count = 0;
        for (Message message : messages) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Search cancelled");
            }
            
            CachedMessage cachedMsg = (CachedMessage) message;
            String subject = cachedMsg.getSubject();
            String from = cachedMsg.getCleanFrom();
            java.util.Date date = cachedMsg.getSentDate();
            
            result.append(++count).append(". ")
                  .append(date != null ? date.toString() : "Unknown date")
                  .append(" - From: ").append(from)
                  .append(" - Subject: ").append(subject)
                  .append("\n");
            
            int progress = 50 + (count * 50 / messages.length);
            callback.update(progress, "Processing message " + count + " of " + messages.length);
        }
        
        callback.update(100, "Search completed");
        return result.toString();
    }
}