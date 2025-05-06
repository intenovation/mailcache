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
        super("apply-"+messageTask.getName(), "Apply '" + messageTask.getName() + "' to all messages in folder");
        this.messageTask = messageTask;
    }
    
    @Override
    protected String executeOnFolder(ProgressStatusCallback callback, CachedFolder folder) 
            throws InterruptedException {
        
        callback.update(0, "Opening folder: " + folder.getFullName());
        
        try {
            folder.open(javax.mail.Folder.READ_WRITE);
            
            try {
                int messageCount=folder.getMessageCount();
                callback.update(0, "messageCount: " + messageCount + " messages");
                Message[] messages = folder.getMessages();
                int totalMessages = messages.length;

                if (totalMessages != messageCount) {
                    return totalMessages+" totalMessages vs messageCount "+messageCount;
                }

                if (totalMessages == 0) {
                    return "No messages in folder";
                }
                
                callback.update(1, "Processing " + totalMessages + " messages");
                
                int successCount = 0;
                int errorCount = 0;
                String result="";
                for (int i = 0; i < totalMessages; i++) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Task cancelled");
                    }
                    
                    Message message = messages[i];
                    int progress = (i * 100) / totalMessages;
                    
                    try {
                        callback.update(progress, "Processing message ? /" + totalMessages);
                        
                        if (message instanceof CachedMessage) {
                            CachedMessage cachedMessage = (CachedMessage) message;
                            
                            // Create a sub-progress callback
                            ProgressStatusCallback subCallback = new ProgressStatusCallback() {
                                @Override
                                public void update(int subPercent, String subMessage) {
                                    // Don't update main progress, just status
                                    callback.update(progress, "Message : " + subMessage);
                                }
                            };
                            
                            // Execute the message task
                            result+=i+" "+messageTask.getName()+":";
                            result+=messageTask.executeOnMessage(subCallback, cachedMessage);
                            result+="\n";
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
                // apply-read rechnung@intenovation.com:2024/amwater.com
                return result+"Processed " + totalMessages + " messages. Success: " + successCount + ", Errors: " + errorCount;
                
            } finally {
                folder.close(false);
            }
            
        } catch (MessagingException e) {
            throw new RuntimeException("Error accessing folder: " + e.getMessage(), e);
        }
    }
}