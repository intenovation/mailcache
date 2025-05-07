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
import javax.mail.search.FromStringTerm;
import javax.mail.search.SearchTerm;
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Task that searches messages by sender email address.
 */
public class SearchBySenderTask extends BackgroundTask {
    private static final Logger LOGGER = Logger.getLogger(SearchBySenderTask.class.getName());
    
    public SearchBySenderTask() {
        super("search-by-sender", "Search for messages from a specific sender");
    }
    
    @Override
    public ConfigItemType getParameterType() {
        return StandardConfigItemTypes.TEXT;
    }
    
    @Override
    public String getParameterName() {
        return "Email Address";
    }
    
    @Override
    public String getParameterDescription() {
        return "Full or partial email address to search for in the From field";
    }
    
    @Override
    public String execute(ProgressStatusCallback callback, Object parameter) 
            throws InterruptedException {
        
        if (!(parameter instanceof String)) {
            return "Invalid parameter: Expected a string containing an email address";
        }
        
        String emailPart = (String) parameter;
        if (emailPart.isEmpty()) {
            return "Email address cannot be empty";
        }
        
        callback.update(0, "Preparing to search for sender: " + emailPart);
        
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
                // Create a search term for the sender
                SearchTerm searchTerm = new FromStringTerm(emailPart);
                
                // Perform the search
                callback.update(20, "Executing search query...");
                Message[] messages = inbox.search(searchTerm);
                
                if (messages == null || messages.length == 0) {
                    return "No messages found from: " + emailPart;
                }
                
                // Process and display results
                callback.update(50, "Found " + messages.length + " messages. Processing...");
                
                StringBuilder result = new StringBuilder();
                result.append("Found ").append(messages.length).append(" messages from: ").append(emailPart).append("\n\n");
                
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
}