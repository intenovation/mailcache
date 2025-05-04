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
}