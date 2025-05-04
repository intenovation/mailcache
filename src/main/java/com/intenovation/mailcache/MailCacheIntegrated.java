package com.intenovation.mailcache;

import com.intenovation.appfw.app.AppController;
import com.intenovation.appfw.app.Application;
import com.intenovation.passwordmanager.PasswordManagerApp;
import com.intenovation.mailcache.MailCacheCLI;

import java.util.Arrays;
import java.util.List;

/**
 * Integrated application launcher that combines PasswordManager and MailCache CLI
 */
public class MailCacheIntegrated {
    
    public static void main(String[] args) {
        // Create both applications
        PasswordManagerApp passwordManagerApp = new PasswordManagerApp();
        MailCacheCLI mailCacheApp = new MailCacheCLI();
        
        // Create a list of applications
        List<Application> applications = Arrays.asList(passwordManagerApp, mailCacheApp);
        
        // Create the app controller
        AppController controller = new AppController(applications);
        
        // Set MailCache as the default application
        controller.setDefaultApplication("MailCache CLI");
        
        // Process command line arguments
        int exitCode = controller.processCommandLine(args);
        
        // Exit with the appropriate code
        System.exit(exitCode);
    }
}