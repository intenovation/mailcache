package com.intenovation.mailcache;

import com.intenovation.mailcache.CachedStore;
import com.intenovation.mailcache.CacheMode;
import com.intenovation.mailcache.integration.MailCacheManager;
import com.intenovation.passwordmanager.PasswordManagerApp;

import javax.mail.Folder;
import javax.mail.Message;
import java.io.File;
import java.util.Map;
import java.util.Scanner;

/**
 * Demo application showing integration of MailCache with Password Manager
 */
public class MailCacheDemo {
    
    public static void main(String[] args) {
        try {
            // Initialize password manager
            PasswordManagerApp passwordManager = new PasswordManagerApp();
            
            // Create default cache directory
            File cacheDir = new File(System.getProperty("user.home"), "mailcache");
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            
            // Initialize mail cache manager
            MailCacheManager mailCacheManager = new MailCacheManager(passwordManager, cacheDir);
            
            // Show menu
            Scanner scanner = new Scanner(System.in);
            boolean exit = false;
            
            while (!exit) {
                System.out.println("\nMail Cache Manager Demo");
                System.out.println("1. Add IMAP Account");
                System.out.println("2. Initialize All Stores");
                System.out.println("3. List Open Stores");
                System.out.println("4. Change Cache Mode");
                System.out.println("5. List Messages");
                System.out.println("6. Open Store in Offline Mode");
                System.out.println("7. Close All Stores and Exit");
                System.out.print("Enter choice: ");
                
                String choice = scanner.nextLine();
                
                switch (choice) {
                    case "1":
                        // Add IMAP account
                        System.out.print("Username: ");
                        String username = scanner.nextLine();
                        
                        System.out.print("Password: ");
                        String password = scanner.nextLine();
                        
                        System.out.print("IMAP Server: ");
                        String server = scanner.nextLine();
                        
                        System.out.print("Port (default 993): ");
                        String portStr = scanner.nextLine();
                        int port = 993;
                        if (!portStr.isEmpty()) {
                            try {
                                port = Integer.parseInt(portStr);
                            } catch (NumberFormatException e) {
                                System.out.println("Invalid port, using default 993");
                            }
                        }
                        
                        System.out.print("Use SSL (y/n, default y): ");
                        String sslStr = scanner.nextLine();
                        boolean useSSL = true;
                        if (!sslStr.isEmpty() && sslStr.toLowerCase().startsWith("n")) {
                            useSSL = false;
                        }
                        
                        // Show cache mode options
                        System.out.println("Cache modes:");
                        for (CacheMode mode : CacheMode.values()) {
                            System.out.println("- " + mode + ": " + mode.getDescription());
                        }
                        
                        System.out.print("Cache mode (default ACCELERATED): ");
                        String modeStr = scanner.nextLine();
                        CacheMode cacheMode = CacheMode.ACCELERATED;
                        if (!modeStr.isEmpty()) {
                            try {
                                cacheMode = CacheMode.valueOf(modeStr.toUpperCase());
                            } catch (IllegalArgumentException e) {
                                System.out.println("Invalid mode, using default ACCELERATED");
                            }
                        }
                        
                        System.out.print("Custom cache directory (leave empty for default): ");
                        String cacheDirStr = scanner.nextLine();
                        File customCacheDir = null;
                        if (!cacheDirStr.isEmpty()) {
                            customCacheDir = new File(cacheDirStr);
                        }
                        
                        boolean success = mailCacheManager.addImapAccount(
                                username, password, server, port, useSSL, cacheMode, customCacheDir);
                        
                        if (success) {
                            System.out.println("Account added successfully");
                        } else {
                            System.out.println("Failed to add account");
                        }
                        break;
                        
                    case "2":
                        // Initialize all stores
                        Map<String, CachedStore> stores = mailCacheManager.initializeAllStores();
                        System.out.println("Initialized " + stores.size() + " stores");
                        break;
                        
                    case "3":
                        // List open stores
                        Map<String, CachedStore> openStores = mailCacheManager.getAllStores();
                        if (openStores.isEmpty()) {
                            System.out.println("No open stores");
                        } else {
                            System.out.println("Open stores:");
                            for (Map.Entry<String, CachedStore> entry : openStores.entrySet()) {
                                System.out.println(entry.getKey() + " - " + entry.getValue().getMode());
                            }
                        }
                        break;
                        
                    case "4":
                        // Change cache mode
                        openStores = mailCacheManager.getAllStores();
                        if (openStores.isEmpty()) {
                            System.out.println("No open stores");
                            break;
                        }
                        
                        System.out.println("Open stores:");
                        int i = 1;
                        String[] usernames = new String[openStores.size()];
                        for (String name : openStores.keySet()) {
                            usernames[i-1] = name;
                            System.out.println(i + ". " + name);
                            i++;
                        }
                        
                        System.out.print("Select store: ");
                        String indexStr = scanner.nextLine();
                        int index;
                        try {
                            index = Integer.parseInt(indexStr);
                            if (index < 1 || index > usernames.length) {
                                System.out.println("Invalid selection");
                                break;
                            }
                        } catch (NumberFormatException e) {
                            System.out.println("Invalid input");
                            break;
                        }
                        
                        String selectedUsername = usernames[index-1];
                        
                        // Show cache mode options
                        System.out.println("Cache modes:");
                        for (CacheMode mode : CacheMode.values()) {
                            System.out.println("- " + mode + ": " + mode.getDescription());
                        }
                        
                        System.out.print("New cache mode: ");
                        modeStr = scanner.nextLine();
                        try {
                            cacheMode = CacheMode.valueOf(modeStr.toUpperCase());
                            
                            boolean updateSuccess = mailCacheManager.updateCacheMode(
                                    selectedUsername, cacheMode);
                            
                            if (updateSuccess) {
                                System.out.println("Cache mode updated successfully");
                            } else {
                                System.out.println("Failed to update cache mode");
                            }
                        } catch (IllegalArgumentException e) {
                            System.out.println("Invalid cache mode");
                        }
                        break;
                        
                    case "5":
                        // List messages
                        openStores = mailCacheManager.getAllStores();
                        if (openStores.isEmpty()) {
                            System.out.println("No open stores");
                            break;
                        }
                        
                        System.out.println("Open stores:");
                        i = 1;
                        usernames = new String[openStores.size()];
                        for (String name : openStores.keySet()) {
                            usernames[i-1] = name;
                            System.out.println(i + ". " + name);
                            i++;
                        }
                        
                        System.out.print("Select store: ");
                        indexStr = scanner.nextLine();
                        try {
                            index = Integer.parseInt(indexStr);
                            if (index < 1 || index > usernames.length) {
                                System.out.println("Invalid selection");
                                break;
                            }
                        } catch (NumberFormatException e) {
                            System.out.println("Invalid input");
                            break;
                        }
                        
                        selectedUsername = usernames[index-1];
                        CachedStore store = mailCacheManager.getStore(selectedUsername);
                        
                        System.out.print("Folder name (default INBOX): ");
                        String folderName = scanner.nextLine();
                        if (folderName.isEmpty()) {
                            folderName = "INBOX";
                        }
                        
                        try {
                            Folder folder = store.getFolder(folderName);
                            if (!folder.exists()) {
                                System.out.println("Folder doesn't exist: " + folderName);
                                break;
                            }
                            
                            folder.open(Folder.READ_ONLY);
                            int count = folder.getMessageCount();
                            System.out.println("Found " + count + " messages in " + folderName);
                            
                            if (count > 0) {
                                System.out.print("How many messages to show (default 10): ");
                                String countStr = scanner.nextLine();
                                int showCount = 10;
                                if (!countStr.isEmpty()) {
                                    try {
                                        showCount = Integer.parseInt(countStr);
                                    } catch (NumberFormatException e) {
                                        System.out.println("Invalid count, using default 10");
                                    }
                                }
                                
                                showCount = Math.min(showCount, count);
                                Message[] messages = folder.getMessages(1, showCount);
                                
                                for (int j = 0; j < messages.length; j++) {
                                    System.out.println((j+1) + ". " + messages[j].getSubject());
                                }
                            }
                            
                            folder.close(false);
                        } catch (Exception e) {
                            System.out.println("Error: " + e.getMessage());
                        }
                        break;
                        
                    case "6":
                        // Open store in offline mode
                        System.out.print("Username to open in offline mode: ");
                        String offlineUsername = scanner.nextLine();
                        
                        CachedStore offlineStore = mailCacheManager.openOfflineStore(offlineUsername);
                        if (offlineStore != null) {
                            System.out.println("Opened offline store for " + offlineUsername);
                        } else {
                            System.out.println("Failed to open offline store");
                        }
                        break;
                        
                    case "7":
                        // Close all stores and exit
                        mailCacheManager.closeAllStores();
                        exit = true;
                        break;
                        
                    default:
                        System.out.println("Invalid choice");
                        break;
                }
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}