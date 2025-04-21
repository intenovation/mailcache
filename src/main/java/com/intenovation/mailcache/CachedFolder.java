package com.intenovation.mailcache;

import javax.mail.*;
import javax.mail.internet.MimeMessage;
import javax.mail.search.SearchTerm;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A JavaMail Folder implementation that supports the cache modes
 */
public class CachedFolder extends Folder {
    private static final Logger LOGGER = Logger.getLogger(CachedFolder.class.getName());

    private CachedStore cachedStore;
    public Folder imapFolder;
    private File cacheDir;
    private String folderName;
    private boolean isOpen = false;
    private int mode = -1;

    /**
     * Create a new CachedFolder
     */
    public CachedFolder(CachedStore store, String name, boolean createDirectory) {
        super(store);
        this.cachedStore = store;
        this.folderName = name;
        if (store.getCacheDirectory() != null) {
            this.cacheDir = new File(store.getCacheDirectory(),
                    name.replace('/', File.separatorChar));
        }
        // Create cache directory for this folder
        if (createDirectory && store.getCacheDirectory() != null) {
            if (!this.cacheDir.exists()) {
                this.cacheDir.mkdirs();
            }

            // Create messages directory
            File messagesDir = new File(cacheDir, "messages");
            if (!messagesDir.exists()) {
                messagesDir.mkdirs();
            }

            // Create archived messages directory
            File archivedDir = new File(cacheDir, "archived_messages");
            if (!archivedDir.exists()) {
                archivedDir.mkdirs();
            }
        }

        // For ONLINE, ACCELERATED, and DESTRUCTIVE modes, get the corresponding IMAP folder
        if (store.getMode() != CacheMode.OFFLINE && store.getImapStore() != null) {
            try {
                this.imapFolder = store.getImapStore().getFolder(name);
            } catch (MessagingException e) {
                // Log exception but continue
                LOGGER.log(Level.WARNING, "Could not get IMAP folder: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Get the cache directory for this folder
     */
    public File getCacheDir() {
        return cacheDir;
    }

    @Override
    public String getName() {
        return folderName;
    }

    @Override
    public String getFullName() {
        return folderName;
    }

    @Override
    public CachedFolder getParent() throws MessagingException {
        int lastSlash = folderName.lastIndexOf('/');
        if (lastSlash == -1) {
            return cachedStore.getDefaultFolder();
        } else {
            String parentName = folderName.substring(0, lastSlash);
            return new CachedFolder(cachedStore, parentName, true);
        }
    }

    @Override
    public boolean exists() throws MessagingException {
        // Check cache directory first
        if (cacheDir != null && cacheDir.exists()) {
            return true;
        }

        // For ONLINE and other non-OFFLINE modes, also check IMAP
        if (cachedStore.getMode() != CacheMode.OFFLINE && imapFolder != null) {
            boolean imapFolderExists=imapFolder.exists();
            if (imapFolderExists){
                //exists remotely, so this is not synched. We do not have to create it remotely,
                //let us create it locally, fix the synch problem
                this.createLocally();
            }
            return imapFolderExists;
        }

        return false;
    }

    @Override
    public CachedFolder[] list(String pattern) throws MessagingException {
        List<Folder> folders = new ArrayList<>();

        // For OFFLINE mode or when caching, list from cache
        if (cachedStore.getMode() == CacheMode.OFFLINE || cacheDir != null) {
            File[] subdirs = cacheDir.listFiles(File::isDirectory);
            if (subdirs != null) {
                for (File subdir : subdirs) {
                    // Skip special directories like "messages" and "archived_messages"
                    if (!subdir.getName().equals("messages") && !subdir.getName().equals("archived_messages") && !subdir.getName().equals("extras")) {
                        String childName = folderName.isEmpty() ?
                                subdir.getName() :
                                folderName + "/" + subdir.getName();
                        folders.add(new CachedFolder(cachedStore, childName, false));
                    }
                }
            }
        }

        // For non-OFFLINE modes, also list from IMAP
        if (cachedStore.getMode() != CacheMode.OFFLINE && imapFolder != null) {
            Folder[] imapFolders = imapFolder.list(pattern);
            for (Folder folder : imapFolders) {
                // Check if already added from cache
                boolean found = false;
                for (Folder cacheFolder : folders) {
                    if (cacheFolder.getFullName().equals(folder.getFullName())) {
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    folders.add(new CachedFolder(cachedStore, folder.getFullName(), true));
                }
            }
        }

        return folders.toArray(new CachedFolder[0]);
    }

    @Override
    public CachedFolder[] list() throws MessagingException {
        return list("%");
    }

    @Override
    public CachedFolder getFolder(String name) throws MessagingException {
        // Build the full name
        String fullName = folderName.isEmpty() ? name : folderName + "/" + name;
        return new CachedFolder(cachedStore, fullName, true);
    }


    @Override
    public char getSeparator() throws MessagingException {
        return '/';
    }

    @Override
    public int getType() throws MessagingException {
        return HOLDS_MESSAGES | HOLDS_FOLDERS;
    }

    @Override
    public boolean create(int type) throws MessagingException {
        // In OFFLINE mode, only create locally
        if (cachedStore.getMode() == CacheMode.OFFLINE) {
            throw new MessagingException("Cannot create folders in offline mode");

        }

        // In other modes, try to create on server first
        boolean success = true;

        // Create on server if possible
        if (imapFolder != null) {
            try {
                success = imapFolder.create(type);
                if (!success) {
                    return false; // Don't continue if server operation failed
                }
            } catch (MessagingException e) {
                LOGGER.log(Level.SEVERE, "Error creating folder on server", e);
                throw e; // Don't continue if server operation failed
            }
        }

        // Then create locally
        return createLocally();

    }

    private boolean createLocally() {
        if (cacheDir != null && !cacheDir.exists()) {
            boolean result = cacheDir.mkdirs();

            // Create messages directory
            File messagesDir = new File(cacheDir, "messages");
            if (!messagesDir.exists()) {
                messagesDir.mkdirs();
            }

            // Create archived messages directory
            File archivedDir = new File(cacheDir, "archived_messages");
            if (!archivedDir.exists()) {
                archivedDir.mkdirs();
            }

            return true;
        }
        return false;
    }

    @Override
    public boolean delete(boolean recurse) throws MessagingException {
        // Only allow deletion in DESTRUCTIVE mode
        if (cachedStore.getMode() != CacheMode.DESTRUCTIVE) {
            throw new MessagingException("Cannot delete folders unless in DESTRUCTIVE mode");
        }

        boolean success = true;

        // Delete on server if possible
        if (imapFolder != null) {
            try {
                success = imapFolder.delete(recurse);
                if (!success) {
                    return false; // Don't delete locally if server delete failed
                }
            } catch (MessagingException e) {
                LOGGER.log(Level.WARNING, "Error deleting folder on server: " + e.getMessage(), e);
                return false;
            }
        }

        // Only delete locally if server operation was successful
        if (cacheDir != null) {
            // Actually, instead of deleting, move to an archive directory
            File archiveDir = new File(cachedStore.getCacheDirectory(), "archived_folders");
            if (!archiveDir.exists()) {
                archiveDir.mkdirs();
            }

            String timestamp = String.valueOf(System.currentTimeMillis());
            File archiveFolder = new File(archiveDir, folderName.replace('/', '_') + "_" + timestamp);

            return cacheDir.renameTo(archiveFolder);
        }

        return success;
    }

    private boolean deleteRecursive(File dir) {
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteRecursive(file);
                }
            }
        }
        return dir.delete();
    }

    @Override
    public boolean renameTo(Folder folder) throws MessagingException {
        // In OFFLINE mode, cannot rename
        if (cachedStore.getMode() == CacheMode.OFFLINE) {
            throw new MessagingException("Cannot rename folders in OFFLINE mode");
        }

        boolean success = true;

        // Rename on server if possible
        if (imapFolder != null && folder instanceof CachedFolder) {
            try {
                CachedFolder cachedFolder = (CachedFolder) folder;
                if (cachedFolder.imapFolder != null) {
                    success = imapFolder.renameTo(cachedFolder.imapFolder);
                    if (!success) {
                        return false; // Don't rename locally if server rename failed
                    }
                }
            } catch (MessagingException e) {
                LOGGER.log(Level.WARNING, "Error renaming folder on server: " + e.getMessage(), e);
                return false;
            }
        }

        // Only rename locally if server operation was successful
        if (cacheDir != null && folder instanceof CachedFolder) {
            CachedFolder cachedFolder = (CachedFolder) folder;
            if (cachedFolder.cacheDir != null) {
                return cacheDir.renameTo(cachedFolder.cacheDir);
            }
        }

        return success;
    }

    @Override
    public void open(int mode) throws MessagingException {
        if (isOpen) {
            throw new IllegalStateException("Folder is already open");
        }

        this.mode = mode;

        // For non-OFFLINE modes, open IMAP folder
        if (cachedStore.getMode() != CacheMode.OFFLINE && imapFolder != null ) {
            imapFolder.open(mode);
        }

        isOpen = true;
    }

    @Override
    public void close(boolean expunge) throws MessagingException {
        if (!isOpen) {
            throw new IllegalStateException("Folder is not open");
        }
        try {
            // For non-OFFLINE modes, close IMAP folder
            if (cachedStore.getMode() != CacheMode.OFFLINE && imapFolder != null&&imapFolder.isOpen()) {
                imapFolder.close(expunge);
            }
        }catch (Exception e){
            e.printStackTrace();
        }

        isOpen = false;
        this.mode = -1;
    }

    @Override
    public boolean isOpen() {
        return isOpen;
    }

    @Override
    public Flags getPermanentFlags() {
        try {
            // For non-OFFLINE modes, get from IMAP
            if (cachedStore.getMode() != CacheMode.OFFLINE && imapFolder != null && imapFolder.isOpen()) {
                return imapFolder.getPermanentFlags();
            }
        } catch (Exception e) {
            // Fallback to empty flags
        }

        return new Flags();
    }

    @Override
    public CachedMessage[] getMessages(int start, int end) throws MessagingException {
        checkOpen();

        // For ONLINE mode with search, get from IMAP
        if (cachedStore.getMode() == CacheMode.ONLINE && imapFolder != null && imapFolder.isOpen()) {
            // Get from IMAP
            Message[] imapMessages = imapFolder.getMessages(start, end);

            // Create cached versions
            CachedMessage[] cachedMessages = new CachedMessage[imapMessages.length];
            for (int i = 0; i < imapMessages.length; i++) {
                cachedMessages[i] = new CachedMessage(this, imapMessages[i]);
            }

            return cachedMessages;
        }

        // For other modes, get from local cache
        List<CachedMessage> messages = new ArrayList<>();

        if (cacheDir != null) {
            File messagesDir = new File(cacheDir, "messages");
            if (messagesDir.exists()) {
                File[] messageDirs = messagesDir.listFiles(File::isDirectory);

                if (messageDirs != null) {
                    // Sort directories to ensure consistent order
                    // (implementation would sort by message number or date)
                    java.util.Arrays.sort(messageDirs);

                    int count = 0;
                    for (File messageDir : messageDirs) {
                        count++;

                        if (count >= start && count <= end) {
                            try {
                                messages.add(new CachedMessage(this, messageDir));
                            } catch (MessagingException e) {
                                LOGGER.log(Level.WARNING, "Error loading message: " + e.getMessage(), e);
                            }
                        }

                        if (count > end) {
                            break;
                        }
                    }
                }
            }
        }

        return messages.toArray(new CachedMessage[0]);
    }

    @Override
    public CachedMessage[] getMessages() throws MessagingException {
        checkOpen();

        // For ONLINE mode, get from IMAP
        if (cachedStore.getMode() == CacheMode.ONLINE && imapFolder != null && imapFolder.isOpen()) {
            // Get from IMAP
            Message[] imapMessages = imapFolder.getMessages();

            // Create cached versions
            CachedMessage[] cachedMessages = new CachedMessage[imapMessages.length];
            for (int i = 0; i < imapMessages.length; i++) {
                cachedMessages[i] = new CachedMessage(this, imapMessages[i]);
            }

            return cachedMessages;
        }

        // For other modes, get from local cache
        List<CachedMessage> messages = new ArrayList<>();

        if (cacheDir != null) {
            File messagesDir = new File(cacheDir, "messages");
            if (messagesDir.exists()) {
                File[] messageDirs = messagesDir.listFiles(File::isDirectory);

                if (messageDirs != null) {
                    // Sort directories to ensure consistent order
                    java.util.Arrays.sort(messageDirs);

                    for (File messageDir : messageDirs) {
                        try {
                            messages.add(new CachedMessage(this, messageDir));
                        } catch (MessagingException e) {
                            LOGGER.log(Level.WARNING, "Error loading message: " + e.getMessage(), e);
                        }
                    }
                }
            }
        }

        return messages.toArray(new CachedMessage[0]);
    }

    @Override
    public int getMessageCount() throws MessagingException {
        checkOpen();

        // For ONLINE mode, get from IMAP
        if (cachedStore.getMode() == CacheMode.ONLINE && imapFolder != null && imapFolder.isOpen()) {
            try {
                return imapFolder.getMessageCount();
            } catch (MessagingException e) {
                // Fall back to local cache if server check fails
                LOGGER.log(Level.WARNING, "Error getting message count from server", e);
            }
        }

        // For other modes, count from cache
        if (cacheDir != null) {
            File messagesDir = new File(cacheDir, "messages");
            if (messagesDir.exists()) {
                File[] messageDirs = messagesDir.listFiles(File::isDirectory);
                if (messageDirs != null) {
                    return messageDirs.length;
                }
            }
        }

        return 0;
    }

    @Override
    public CachedMessage getMessage(int msgnum) throws MessagingException {
        checkOpen();

        // For ONLINE mode, get from IMAP
        if (cachedStore.getMode() == CacheMode.ONLINE && imapFolder != null && imapFolder.isOpen()) {
            // Get from IMAP
            Message imapMessage = imapFolder.getMessage(msgnum);

            // Create cached version
            return new CachedMessage(this, imapMessage);
        }

        // For other modes, get from local cache
        if (cacheDir != null) {
            File messagesDir = new File(cacheDir, "messages");
            if (messagesDir.exists()) {
                File[] messageDirs = messagesDir.listFiles(File::isDirectory);

                if (messageDirs != null) {
                    // Sort directories to ensure consistent order
                    java.util.Arrays.sort(messageDirs);

                    if (msgnum > 0 && msgnum <= messageDirs.length) {
                        return new CachedMessage(this, messageDirs[msgnum - 1]);
                    }
                }
            }
        }

        throw new MessagingException("Message number " + msgnum + " not found");
    }

    @Override
    public void appendMessages(Message[] msgs) throws MessagingException {
        // In OFFLINE mode, cannot append
        if (cachedStore.getMode() == CacheMode.OFFLINE) {
            throw new MessagingException("Cannot append messages in OFFLINE mode");
        }

        checkOpen();

        // For other modes, append to server first
        if (imapFolder != null && imapFolder.isOpen()) {
            try {
                // Extract or convert IMAP messages for server operation
                Message[] imapMessages = new Message[msgs.length];
                for (int i = 0; i < msgs.length; i++) {
                    if (msgs[i] instanceof CachedMessage) {
                        CachedMessage cachedMsg = (CachedMessage) msgs[i];
                        // Get the underlying IMAP message if available
                        Message imapMsg = cachedMsg.getImapMessage();
                        if (imapMsg != null) {
                            imapMessages[i] = imapMsg;
                        } else {
                            // Create a new MimeMessage from the cached content
                            imapMessages[i] = new MimeMessage(cachedStore.getSession(),
                                    cachedMsg.getInputStream());
                        }
                    } else {
                        imapMessages[i] = msgs[i];
                    }
                }

                // Now append the IMAP messages to the IMAP folder
                imapFolder.appendMessages(imapMessages);
            } catch (MessagingException e) {
                LOGGER.log(Level.SEVERE, "Error appending messages to server", e);
                throw e; // Don't continue if server operation failed

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error appending messages to server", e);
            throw new MessagingException("Error appending messages to server",e); // Don't continue if server operation failed
        }
        }

        // Then handle local cache operations
        if (cacheDir != null) {
            File messagesDir = new File(cacheDir, "messages");
            if (!messagesDir.exists()) {
                messagesDir.mkdirs();
            }

            for (Message msg : msgs) {
                // If it's already a CachedMessage for this folder, skip
                if (msg instanceof CachedMessage) {
                    CachedMessage cachedMsg = (CachedMessage) msg;
                    if (cachedMsg.getFolder() == this) {
                        continue; // Already cached in this folder
                    }
                }

                // Create a new CachedMessage - this will handle proper caching
                try {
                    new CachedMessage(this, msg);
                } catch (MessagingException e) {
                    LOGGER.log(Level.WARNING, "Error caching message: " + e.getMessage(), e);
                    // Continue with other messages
                }
            }
        }
    }

    @Override
    public int getUnreadMessageCount() throws MessagingException {
        checkOpen();

        // For ONLINE mode, get from IMAP
        if (cachedStore.getMode() == CacheMode.ONLINE && imapFolder != null && imapFolder.isOpen()) {
            try {
                return imapFolder.getUnreadMessageCount();
            } catch (MessagingException e) {
                // Fall back to local cache if server check fails
                LOGGER.log(Level.WARNING, "Error getting unread message count from server", e);
            }
        }

        // For other modes, count unread messages from cache
        int unreadCount = 0;
        if (cacheDir != null) {
            try {
                Message[] messages = getMessages();
                for (Message message : messages) {
                    if (!message.isSet(Flags.Flag.SEEN)) {
                        unreadCount++;
                    }
                }
            } catch (MessagingException e) {
                LOGGER.log(Level.WARNING, "Error counting unread messages in cache", e);
            }
        }

        return unreadCount;
    }

    @Override
    public int getNewMessageCount() throws MessagingException {
        // For OFFLINE mode, we cannot determine new message count
        if (cachedStore.getMode() == CacheMode.OFFLINE) {
            return 0;
        }

        // For non-OFFLINE modes, check the IMAP folder
        if (imapFolder != null && imapFolder.isOpen()) {
            try {
                return imapFolder.getNewMessageCount();
            } catch (MessagingException e) {
                // Log error but return 0
                LOGGER.log(Level.WARNING, "Error getting new message count from server", e);
            }
        }

        return 0;
    }

    @Override
    public boolean hasNewMessages() throws MessagingException {
        // For OFFLINE mode, we cannot determine if there are new messages
        if (cachedStore.getMode() == CacheMode.OFFLINE) {
            return false;
        }

        // For non-OFFLINE modes, check the IMAP folder
        if (imapFolder != null) {
            try {
                return imapFolder.hasNewMessages();
            } catch (MessagingException e) {
                // Fall back to local check if server check fails
                LOGGER.log(Level.WARNING, "Error checking for new messages on server", e);
            }
        }

        return false;
    }

    @Override
    public Message[] expunge() throws MessagingException {
        // Only allow expunge in DESTRUCTIVE mode
        // we can not return CachedMessage because this came from imap
        if (cachedStore.getMode() != CacheMode.DESTRUCTIVE) {
            throw new MessagingException("Cannot expunge messages unless in DESTRUCTIVE mode");
        }

        checkOpen();

        // Expunge from server first
        Message[] expunged = null;
        if (imapFolder != null && imapFolder.isOpen()) {
            expunged = imapFolder.expunge();
        }

        // For DESTRUCTIVE mode, we can remove messages from the local cache
        // but we'll keep a backup in an "archive" directory
        if (expunged != null && expunged.length > 0 && cacheDir != null) {
            File messagesDir = new File(cacheDir, "messages");
            File archiveDir = new File(cacheDir, "archived_messages");
            if (!archiveDir.exists()) {
                archiveDir.mkdirs();
            }

            if (messagesDir.exists()) {
                for (Message msg : expunged) {
                    if (msg instanceof CachedMessage) {
                        CachedMessage cachedMsg = (CachedMessage) msg;
                        File messageDir = cachedMsg.getMessageDirectory();

                        // Move to archive instead of deleting
                        if (messageDir != null && messageDir.exists()) {
                            File archiveDest = new File(archiveDir, messageDir.getName());
                            messageDir.renameTo(archiveDest);
                        }
                    }
                }
            }
        }

        return expunged != null ? expunged : new CachedMessage[0];
    }


    public CachedMessage[] search(SearchTerm term) throws MessagingException {
        checkOpen();

        // For ONLINE mode, search on server
        if (cachedStore.getMode() == CacheMode.ONLINE && imapFolder != null && imapFolder.isOpen()) {
            try {
                Message[] serverResults = imapFolder.search(term);

                // Create cached versions of the server results
                List<CachedMessage> cachedResults = new ArrayList<>();
                for (Message msg : serverResults) {
                    cachedResults.add(new CachedMessage(this, msg));
                }

                return cachedResults.toArray(new CachedMessage[0]);
            } catch (MessagingException e) {
                LOGGER.log(Level.WARNING, "Error searching on server, falling back to local cache", e);
            }
        }

        // For other modes or if server search fails, search in local cache
        CachedMessage[] messages = getMessages();
        List<CachedMessage> results = new ArrayList<>();

        for (CachedMessage msg : messages) {
            if (term.match(msg)) {
                results.add(msg);
            }
        }

        return results.toArray(new CachedMessage[0]);
    }

    /**
     * Get the file system directory for this folder
     *
     * @return The directory for this folder
     */
    public File getFolderDirectory() {
        return cacheDir;
    }

    /**
     * Add an additional file to the folder
     *
     * @param filename The name of the file to create/update
     * @param content The content to write to the file
     * @throws java.io.IOException If there is an error writing the file
     */
    public void addAdditionalFile(String filename, String content) throws java.io.IOException {
        if (cacheDir == null || !cacheDir.exists()) {
            throw new java.io.IOException("Folder directory does not exist");
        }

        // Sanitize filename to prevent directory traversal
        String sanitizedFilename = filename.replaceAll("[\\\\/:*?\"<>|]", "_");

        // Create extras directory if it doesn't exist
        File extrasDir = new File(cacheDir, "extras");
        if (!extrasDir.exists()) {
            extrasDir.mkdirs();
        }

        // Write the file
        File file = new File(extrasDir, sanitizedFilename);
        try (java.io.FileWriter writer = new java.io.FileWriter(file)) {
            writer.write(content);
        }
    }

    /**
     * Get the content of an additional file in this folder
     *
     * @param filename The name of the file to read
     * @return The content of the file, or null if the file doesn't exist
     * @throws java.io.IOException If there is an error reading the file
     */
    public String getAdditionalFileContent(String filename) throws java.io.IOException {
        if (cacheDir == null || !cacheDir.exists()) {
            throw new java.io.IOException("Folder directory does not exist");
        }

        // Sanitize filename to prevent directory traversal
        String sanitizedFilename = filename.replaceAll("[\\\\/:*?\"<>|]", "_");

        File extrasDir = new File(cacheDir, "extras");
        File file = new File(extrasDir, sanitizedFilename);

        if (!file.exists() || !file.isFile()) {
            return null;
        }

        return new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Get a list of all additional files in this folder
     *
     * @return An array of file names
     */
    public String[] listAdditionalFiles() {
        if (cacheDir == null || !cacheDir.exists()) {
            return new String[0];
        }

        File extrasDir = new File(cacheDir, "extras");
        if (!extrasDir.exists() || !extrasDir.isDirectory()) {
            return new String[0];
        }

        File[] files = extrasDir.listFiles(File::isFile);
        if (files == null || files.length == 0) {
            return new String[0];
        }

        String[] filenames = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            filenames[i] = files[i].getName();
        }

        return filenames;
    }

    /**
     * Check if the folder is open
     */
    private void checkOpen() throws MessagingException {
        if (!isOpen) {
            throw new IllegalStateException("Folder is not open");
        }
    }

    /**
     * Sanitize and format a message directory name for safe filesystem storage
     * Format: YYYY-MM-DD_Subject
     */
    private String formatMessageDirName(Message message) throws MessagingException {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        String prefix = "";

        // Get date
        Date sentDate = message.getSentDate();
        if (sentDate != null) {
            prefix = dateFormat.format(sentDate) + "_";
        } else {
            // Use current date if no sent date
            prefix = dateFormat.format(new Date()) + "_";
        }

        // Get subject
        String subject = message.getSubject();
        if (subject == null || subject.isEmpty()) {
            subject = "NoSubject_" + System.currentTimeMillis();
        }

        // Sanitize subject for file system
        String sanitizedSubject = subject.replaceAll("[\\\\/:*?\"<>|]", "_");

        // Limit length to avoid too long file names
        if (sanitizedSubject.length() > 100) {
            sanitizedSubject = sanitizedSubject.substring(0, 100);
        }

        return prefix + sanitizedSubject;
    }

    /**
     * Move messages between folders. Will perform the move on the server first,
     * then update the local cache accordingly.
     *
     * @param messages The messages to move
     * @param destination The destination folder
     * @throws MessagingException If the move operation fails
     */
    public void moveMessages(Message[] messages, Folder destination) throws MessagingException {
        // In OFFLINE mode, cannot move
        if (cachedStore.getMode() == CacheMode.OFFLINE) {
            throw new MessagingException("Cannot move messages in OFFLINE mode");
        }

        checkOpen();

        if (!(destination instanceof CachedFolder)) {
            throw new MessagingException("Destination folder must be a CachedFolder");
        }

        CachedFolder destFolder = (CachedFolder) destination;

        // Move on server first if possible
        if (imapFolder != null && imapFolder.isOpen() && destFolder.imapFolder != null) {
            try {
                // Extract IMAP messages if needed
                List<Message> imapMessages = new ArrayList<>();
                for (Message msg : messages) {
                    if (msg instanceof CachedMessage) {
                        CachedMessage cachedMsg = (CachedMessage) msg;
                        Message imapMsg = cachedMsg.getImapMessage();
                        if (imapMsg != null) {
                            imapMessages.add(imapMsg);
                        }
                    }
                }

                if (!imapMessages.isEmpty()) {
                    // There's no standard moveMessages in JavaMail, so we'd need to implement
                    // using copy + delete in a real implementation

                    // Here's how it would conceptually work:
                    // 1. Copy messages to destination on server
                    destFolder.imapFolder.appendMessages(imapMessages.toArray(new Message[0]));

                    // 2. Mark messages as deleted on server
                    for (Message msg : imapMessages) {
                        msg.setFlag(Flags.Flag.DELETED, true);
                    }

                    // 3. Expunge source folder on server if in DESTRUCTIVE mode
                    if (cachedStore.getMode() == CacheMode.DESTRUCTIVE) {
                        imapFolder.expunge();
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error moving messages on server", e);
                throw new MessagingException("Failed to move messages on server", e);
            }
        }

        // Then update local cache
        if (cacheDir != null && destFolder.cacheDir != null) {
            File srcMessagesDir = new File(cacheDir, "messages");
            File destMessagesDir = new File(destFolder.cacheDir, "messages");

            if (!destMessagesDir.exists()) {
                destMessagesDir.mkdirs();
            }

            for (Message msg : messages) {
                if (msg instanceof CachedMessage) {
                    CachedMessage cachedMsg = (CachedMessage) msg;
                    File messageDir = cachedMsg.getMessageDirectory();

                    if (messageDir != null && messageDir.exists()) {
                        File destMessageDir = new File(destMessagesDir, messageDir.getName());
                        if (destMessageDir.exists()) {
                            throw new MessagingException("Message directory collision during move: " + destMessageDir.getName());
                        }

                        // Move the directory
                        messageDir.renameTo(destMessageDir);
                    }
                }
            }
        }
    }
}