package com.intenovation.mailcache.task;

import com.intenovation.appfw.task.ProgressStatusCallback;
import com.intenovation.mailcache.CachedMessage;

import javax.mail.Message;
import javax.mail.MessagingException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Task that applies a message task to search results.
 */
public abstract class ApplyMessageTaskToSearch extends AbstractSearchTask {
    private static final Logger LOGGER = Logger.getLogger(ApplyMessageTaskToSearch.class.getName());
    
    private final AbstractMessageTask messageTask;
    
    public ApplyMessageTaskToSearch(String name, String description, AbstractMessageTask messageTask) {
        super(name, description);
        this.messageTask = messageTask;
    }
    
    @Override
    protected String processSearchResults(ProgressStatusCallback callback, Message[] messages, String searchTerm)
            throws InterruptedException, MessagingException {
        
        int totalMessages = messages.length;
        if (totalMessages == 0) {
            return "No messages found matching search criteria: " + searchTerm;
        }
        
        callback.update(50, "Applying " + messageTask.getName() + " task to " + totalMessages + " messages");
        
        int successCount = 0;
        int errorCount = 0;
        StringBuilder result = new StringBuilder();
        
        for (int i = 0; i < totalMessages; i++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Task cancelled");
            }
            
            Message message = messages[i];
            int progress = 50 + (i * 50) / totalMessages;
            
            try {
                callback.update(progress, "Processing message " + (i + 1) + "/" + totalMessages);
                
                if (message instanceof CachedMessage) {
                    CachedMessage cachedMessage = (CachedMessage) message;
                    
                    // Create a sub-progress callback
                    ProgressStatusCallback subCallback = new ProgressStatusCallback() {
                        @Override
                        public void update(int subPercent, String subMessage) {
                            // Don't update main progress, just status
                            callback.update(progress, "Message: " + subMessage);
                        }
                    };
                    
                    // Execute the message task
                    String taskResult = messageTask.executeOnMessage(subCallback, cachedMessage);
                    result.append(i + 1).append(" ").append(messageTask.getName()).append(": ");
                    result.append(taskResult);
                    result.append("\n");
                    successCount++;
                } else {
                    errorCount++;
                    LOGGER.warning("Message is not a CachedMessage: " + message);
                }
                
            } catch (Exception e) {
                errorCount++;
                LOGGER.log(Level.WARNING, "Error processing message: " + e.getMessage(), e);
                callback.update(progress, "Error processing message " + (i + 1) + ": " + e.getMessage());
            }
        }
        
        callback.update(100, "Complete");
        
        result.append("\nProcessed ").append(totalMessages).append(" messages. Success: ")
              .append(successCount).append(", Errors: ").append(errorCount);
        
        return result.toString();
    }
    
    /**
     * Get the message task being applied
     * 
     * @return The message task
     */
    protected AbstractMessageTask getMessageTask() {
        return messageTask;
    }
}