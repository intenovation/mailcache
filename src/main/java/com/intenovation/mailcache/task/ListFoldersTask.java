package com.intenovation.mailcache.task;

import com.intenovation.appfw.task.ProgressStatusCallback;
import com.intenovation.mailcache.CachedFolder;

import javax.mail.Folder;
import javax.mail.MessagingException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Task that lists all folders within a parent folder.
 */
public class ListFoldersTask extends AbstractFolderTask {
    private static final Logger LOGGER = Logger.getLogger(ListFoldersTask.class.getName());
    
    private boolean includeMessageCounts = true;
    private boolean recursive = false;
    
    public ListFoldersTask() {
        super("list-folders", "Lists all folders and subfolders");
    }
    
    /**
     * Set whether to include message counts in the folder listing.
     * @param includeMessageCounts true to include message counts, false otherwise
     */
    public void setIncludeMessageCounts(boolean includeMessageCounts) {
        this.includeMessageCounts = includeMessageCounts;
    }
    
    /**
     * Set whether to recursively list subfolders.
     * @param recursive true to list subfolders recursively, false otherwise
     */
    public void setRecursive(boolean recursive) {
        this.recursive = recursive;
    }
    
    @Override
    protected String executeOnFolder(ProgressStatusCallback callback, CachedFolder folder) 
            throws InterruptedException {
        callback.update(0, "Listing folders in: " + folder.getFullName());
        
        List<String> folderInfo = new ArrayList<>();
        
        try {
            // Ensure folder is open if needed for message counts
            boolean wasOpen = folder.isOpen();
            if (includeMessageCounts && !wasOpen) {
                folder.open(Folder.READ_ONLY);
            }
            
            // Get folder information
            try {
                if (folder.getFullName().isEmpty()) {
                    folderInfo.add("Root Folder:");
                } else {
                    StringBuilder info = new StringBuilder(folder.getFullName());
                    
                    if (includeMessageCounts && folder.getType() == (Folder.HOLDS_MESSAGES | Folder.HOLDS_FOLDERS) 
                            || folder.getType() == Folder.HOLDS_MESSAGES) {
                        try {
                            int total = folder.getMessageCount();
                            int unread = folder.getUnreadMessageCount();
                            info.append(" (").append(total).append(" messages, ").append(unread).append(" unread)");
                        } catch (MessagingException e) {
                            info.append(" (error getting message counts)");
                        }
                    }
                    
                    folderInfo.add(info.toString());
                }
                
                // List subfolders
                CachedFolder[] subfolders = folder.list();
                int totalFolders = subfolders.length;
                
                callback.update(10, "Found " + totalFolders + " subfolders");
                
                for (int i = 0; i < totalFolders; i++) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Task cancelled");
                    }
                    
                    CachedFolder subfolder = subfolders[i];
                    int progress = 10 + (i * 90) / Math.max(1, totalFolders);
                    callback.update(progress, "Processing folder " + (i + 1) + "/" + totalFolders + ": " + subfolder.getFullName());
                    
                    // Add folder indentation for hierarchical display
                    StringBuilder info = new StringBuilder("  - " + subfolder.getFullName());
                    
                    // Add message counts if requested
                    if (includeMessageCounts && (subfolder.getType() == (Folder.HOLDS_MESSAGES | Folder.HOLDS_FOLDERS) 
                            || subfolder.getType() == Folder.HOLDS_MESSAGES)) {
                        try {
                            // For performance, only open folders when needed
                            boolean subfolderWasOpen = subfolder.isOpen();
                            if (!subfolderWasOpen) {
                                subfolder.open(Folder.READ_ONLY);
                            }
                            
                            try {
                                int total = subfolder.getMessageCount();
                                int unread = subfolder.getUnreadMessageCount();
                                info.append(" (").append(total).append(" messages, ").append(unread).append(" unread)");
                            } finally {
                                if (!subfolderWasOpen) {
                                    subfolder.close(false);
                                }
                            }
                        } catch (MessagingException e) {
                            info.append(" (error getting message counts)");
                        }
                    }
                    
                    folderInfo.add(info.toString());
                    
                    // Recurse into subfolder if requested
                    if (recursive) {
                        callback.update(progress, "Recursively listing subfolder: " + subfolder.getFullName());
                        
                        // Create a sub-progress callback
                        ProgressStatusCallback subCallback = new ProgressStatusCallback() {
                            @Override
                            public void update(int subPercent, String subMessage) {
                                callback.update(progress, subMessage);
                            }
                        };
                        
                        // Execute recursively
                        String subfolderResult = executeOnFolder(subCallback, subfolder);
                        
                        // Add indented results
                        String[] subLines = subfolderResult.split("\\n");
                        for (String line : subLines) {
                            if (!line.startsWith("Found ") && !line.trim().isEmpty()) {
                                folderInfo.add("    " + line);
                            }
                        }
                    }
                }
                
            } finally {
                // Close the folder if we opened it
                if (includeMessageCounts && !wasOpen) {
                    folder.close(false);
                }
            }
            
            // Build the final result
            StringBuilder result = new StringBuilder();
            for (String line : folderInfo) {
                result.append(line).append("\n");
            }
            
            callback.update(100, "Found " + (folderInfo.size() - 1) + " folders");
            
            return result.toString() + "Found " + (folderInfo.size() - 1) + " folders";
            
        } catch (MessagingException e) {
            LOGGER.log(Level.WARNING, "Error listing folders: " + e.getMessage(), e);
            return "Error listing folders: " + e.getMessage();
        }
    }
}