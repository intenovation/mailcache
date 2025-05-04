package com.intenovation.mailcache.task;

import com.intenovation.appfw.task.ProgressStatusCallback;
import com.intenovation.mailcache.*;

import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;

/**
 * Task that synchronizes a folder with the server.
 */
public class SynchronizeFolderTask extends AbstractFolderTask {
    
    public SynchronizeFolderTask() {
        super("sync-folder", "Synchronize a folder with the server");
    }
    
    @Override
    protected String executeOnFolder(ProgressStatusCallback callback, CachedFolder folder) 
            throws InterruptedException {
        
        callback.update(0, "Opening folder: " + folder.getFullName());
        
        try {
            // Switch to REFRESH mode temporarily
            CachedStore store = folder.getStore();
            CacheMode originalMode = store.getMode();
            
            store.setMode(CacheMode.REFRESH);
            
            folder.open(Folder.READ_ONLY);
            
            try {
                callback.update(10, "Getting messages from server...");
                
                Message[] messages = folder.getMessages();
                int total = messages.length;
                
                callback.update(20, "Found " + total + " messages to synchronize");
                
                for (int i = 0; i < total; i++) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Synchronization cancelled");
                    }
                    
                    int progress = 20 + (i * 80) / total;
                    callback.update(progress, "Synchronizing message " + (i + 1) + "/" + total);
                    
                    // Access the message to trigger caching
                    messages[i].getSubject();
                }
                
                callback.update(100, "Synchronization complete");
                return "Successfully synchronized " + total + " messages";
                
            } finally {
                folder.close(false);
                // Restore original mode
                store.setMode(originalMode);
            }
            
        } catch (MessagingException e) {
            throw new RuntimeException("Error synchronizing folder: " + e.getMessage(), e);
        }
    }
}