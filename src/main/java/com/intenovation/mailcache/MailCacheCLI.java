package com.intenovation.mailcache;

import com.intenovation.appfw.app.AppRunner;
import com.intenovation.appfw.app.Application;
import com.intenovation.appfw.app.CommandLineRunner;

import com.intenovation.appfw.example.ExampleApp;
import com.intenovation.appfw.example.MultiAppExample;
import com.intenovation.passwordmanager.PasswordManagerApp;

import java.util.*;


/**
 * Main CLI application for MailCache that integrates with PasswordManager.
 */
public class MailCacheCLI  {

    public static void main(String[] args) {

        List<Application> apps = Arrays.asList(
                new MailCacheApp(),
                new PasswordManagerApp()
        );

        AppRunner.main(args, apps);

    }
}