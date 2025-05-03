package com.intenovation.mailcache.task;

import com.intenovation.appfw.config.ConfigItemTypeRegistry;
import com.intenovation.appfw.task.BackgroundTask;
import com.intenovation.appfw.task.ProgressStatusCallback;
import com.intenovation.appfw.config.ConfigItemType;
import com.intenovation.mailcache.CachedMessage;
import com.intenovation.mailcache.config.CachedFolderType;
import com.intenovation.mailcache.config.CachedMessageType;

/**
 * Abstract background task that operates on a CachedMessage.
 */
public abstract class AbstractMessageTask extends BackgroundTask {
    
    public AbstractMessageTask(String name, String description) {
        super(name, description);
    }
    
    @Override
    public ConfigItemType getParameterType() {
        return ConfigItemTypeRegistry.getInstance().getType(CachedMessageType.TYPE_ID);
    }
    
    @Override
    public String getParameterName() {
        return "Mail Message";
    }
    
    @Override
    public String getParameterDescription() {
        return "The mail message to process (format: username:folderPath:messageId)";
    }
    
    @Override
    public String execute(ProgressStatusCallback callback, Object parameter) 
            throws InterruptedException {
        if (!(parameter instanceof CachedMessage)) {
            throw new IllegalArgumentException("Parameter must be a CachedMessage");
        }
        
        CachedMessage message = (CachedMessage) parameter;
        return executeOnMessage(callback, message);
    }
    
    /**
     * Execute the task on the given message.
     * 
     * @param callback Progress callback
     * @param message The message to process
     * @return Result message
     * @throws InterruptedException if the task is interrupted
     */
    protected abstract String executeOnMessage(ProgressStatusCallback callback, CachedMessage message)
            throws InterruptedException;
}