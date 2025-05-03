package com.intenovation.mailcache.task;

import com.intenovation.appfw.task.ProgressStatusCallback;
import com.intenovation.mailcache.CachedFolder;
import com.intenovation.mailcache.CachedMessage;
import javax.mail.Message;
import javax.mail.MessagingException;

/**
 * Task that applies a message task to every message in a folder.
 */
public class ApplyMessageTaskToFolder extends AbstractFolderTask {
    
    private final AbstractMessageTask messageTask;
    
    public ApplyMessageTaskToFolder(AbstractMessageTask messageTask) {
        super("apply-to-folder", "Apply '" + messageTask.getName() + "' to all messages in folder");
        this.messageTask = messageTask;
    }
    
    @Override
    protected String executeOnFolder(ProgressStatusCallback callback, CachedFolder folder) 
            throws InterruptedException {
        
        callback.update(0, "Opening folder: " + folder.getFullName());
        
        try {
            folder.open(javax.mail.Folder.READ_WRITE);
            
            try {
                Message[] messages = folder.getMessages();
                int totalMessages = messages.length;
                
                if (totalMessages == 0) {
                    return "No messages in folder";
                }
                
                callback.update(0, "Processing " + totalMessages + " messages");
                
                int successCount = 0;
                int errorCount = 0;
                
                for (int i = 0; i < totalMessages; i++) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Task cancelled");
                    }
                    
                    Message message = messages[i];
                    int progress = (i * 100) / totalMessages;
                    
                    try {
                        callback.update(progress, "Processing message " + (i + 1) + "/" + totalMessages);
                        
                        if (message instanceof CachedMessage) {
                            CachedMessage cachedMessage = (CachedMessage) message;
                            
                            // Create a sub-progress callback
                            ProgressStatusCallback subCallback = new ProgressStatusCallback() {
                                @Override
                                public void update(int subPercent, String subMessage) {
                                    // Don't update main progress, just status
                                    callback.update(progress, "Message " + (i + 1) + ": " + subMessage);
                                }
                            };
                            
                            // Execute the message task
                            messageTask.executeOnMessage(subCallback, cachedMessage);
                            successCount++;
                        } else {
                            errorCount++;
                        }
                        
                    } catch (Exception e) {
                        errorCount++;
                        callback.update(progress, "Error processing message " + (i + 1) + ": " + e.getMessage());
                    }
                }
                
                callback.update(100, "Complete");
                
                return "Processed " + totalMessages + " messages. Success: " + successCount + ", Errors: " + errorCount;
                
            } finally {
                folder.close(false);
            }
            
        } catch (MessagingException e) {
            throw new RuntimeException("Error accessing folder: " + e.getMessage(), e);
        }
    }
}