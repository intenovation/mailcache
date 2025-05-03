package com.intenovation.mailcache.task;

import com.intenovation.appfw.task.BackgroundTask;
import com.intenovation.appfw.task.ProgressStatusCallback;
import com.intenovation.appfw.config.ConfigItemType;
import com.intenovation.mailcache.CachedFolder;
import com.intenovation.mailcache.config.CachedFolderType;

/**
 * Abstract background task that operates on a CachedFolder.
 */
public abstract class AbstractFolderTask extends BackgroundTask {
    
    public AbstractFolderTask(String name, String description) {
        super(name, description);
    }
    
    @Override
    public ConfigItemType getParameterType() {
        return MailCacheTaskRegistry.getInstance().getFolderType();
    }
    
    @Override
    public String getParameterName() {
        return "Mail Folder";
    }
    
    @Override
    public String getParameterDescription() {
        return "The mail folder to process (format: username:folderPath)";
    }
    
    @Override
    public String execute(ProgressStatusCallback callback, Object parameter) 
            throws InterruptedException {
        if (!(parameter instanceof CachedFolder)) {
            throw new IllegalArgumentException("Parameter must be a CachedFolder");
        }
        
        CachedFolder folder = (CachedFolder) parameter;
        return executeOnFolder(callback, folder);
    }
    
    /**
     * Execute the task on the given folder.
     * 
     * @param callback Progress callback
     * @param folder The folder to process
     * @return Result message
     * @throws InterruptedException if the task is interrupted
     */
    protected abstract String executeOnFolder(ProgressStatusCallback callback, CachedFolder folder)
            throws InterruptedException;
}