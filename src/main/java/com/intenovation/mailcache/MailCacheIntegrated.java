package com.intenovation.mailcache;

import com.intenovation.appfw.app.AppController;
import com.intenovation.appfw.app.Application;
import com.intenovation.passwordmanager.PasswordManagerApp;
import com.intenovation.mailcache.MailCacheCLI;

import java.util.Arrays;
import java.util.List;

/**
 * Integrated application launcher that combines PasswordManager and MailCache CLI
 * with proper instance sharing between applications.
 */
public class MailCacheIntegrated {
    
    public static void main(String[] args) {
        // Create password manager first
        PasswordManagerApp passwordManagerApp = new PasswordManagerApp();
        
        // Set integrated mode flag
        System.setProperty("mailcache.integrated", "true");
        
        // Store password manager instance for retrieval by MailCacheCLI
        System.getProperties().put("password.manager.instance", passwordManagerApp);
        
        // Create mail cache app
        MailCacheCLI mailCacheApp = new MailCacheCLI();
        
        // Create a list of applications
        List<Application> applications = Arrays.asList(passwordManagerApp, mailCacheApp);
        
        // Create the app controller
        AppController controller = new AppController(applications);
        
        // Set MailCache as the default application
        controller.setDefaultApplication("MailCache CLI");
        
        // Check if we should just list apps and exit
        if (args.length > 0 && (args[0].equals("--list-apps") || args[0].equals("-l"))) {
            controller.listApplications();
            System.exit(0);
        }
        
        // Process command line arguments
        try {
            int exitCode = controller.processCommandLine(args);
            System.exit(exitCode);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    /**
     * Print usage information
     */
    public static void printUsage() {
        System.out.println("MailCache Integrated CLI");
        System.out.println("------------------------");
        System.out.println("Available commands:");
        System.out.println("  --list-apps, -l      List all available applications");
        System.out.println("  --app \"App Name\"     Select a specific application to run");
        System.out.println("  --list               Alternative way to list applications (same as --list-apps)");
        System.out.println("  --help               Show this help");
        System.out.println("  --repl               Start in interactive REPL mode");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  ./mailcache.sh --list-apps");
        System.out.println("  ./mailcache.sh --app \"Password Manager\" add imap server.com user");
        System.out.println("  ./mailcache.sh --app \"MailCache CLI\" --task sync-folder");
        System.out.println("  ./mailcache.sh --repl");
        System.out.println();
    }
}