package com.intenovation.mailcache;

import com.intenovation.appfw.app.AppController;
import com.intenovation.appfw.app.Application;
import com.intenovation.passwordmanager.PasswordManagerApp;
import com.intenovation.mailcache.MailCacheCLI;

import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * Integrated application launcher that combines PasswordManager and MailCache CLI
 * with proper instance sharing between applications.
 */
public class MailCacheIntegrated {
    private static final Logger LOGGER = Logger.getLogger(MailCacheIntegrated.class.getName());
    
    public static void main(String[] args) {
        // Log the start of application for diagnostics
        LOGGER.info("Starting MailCacheIntegrated with " + args.length + " arguments");
        
        try {
            // Create password manager first
            PasswordManagerApp passwordManagerApp = new PasswordManagerApp();
            LOGGER.info("Created PasswordManagerApp instance");
            
            // Set integrated mode flag
            System.setProperty("mailcache.integrated", "true");
            
            // Store password manager instance for retrieval by MailCacheCLI
            System.getProperties().put("password.manager.instance", passwordManagerApp);
            
            // Create mail cache app and explicitly pass the password manager reference
            MailCacheCLI mailCacheApp = new MailCacheCLI();
            mailCacheApp.setApplicationReference("Password Manager", passwordManagerApp);
            LOGGER.info("Created MailCacheCLI instance with Password Manager reference");
            
            // Create a list of applications
            List<Application> applications = Arrays.asList(passwordManagerApp, mailCacheApp);
            
            // Create the app controller
            AppController controller = new AppController(applications);
            
            // Set MailCache as the default application
            controller.setDefaultApplication("MailCache CLI");
            LOGGER.info("Set MailCache CLI as default application");
            
            // Initialize both applications now to ensure they're properly set up
            for (Application app : applications) {
                app.initialize();
                LOGGER.info("Initialized application: " + app.getName());
            }
            
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
                LOGGER.severe("Error processing command line: " + e.getMessage());
                e.printStackTrace();
                System.exit(1);
            }
        } catch (Exception e) {
            LOGGER.severe("Critical error during application startup: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}