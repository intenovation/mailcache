package com.intenovation.mailcache;

import com.intenovation.appfw.app.CommandLineRunner;

import com.intenovation.passwordmanager.PasswordManagerApp;

import java.util.*;


/**
 * Main CLI application for MailCache that integrates with PasswordManager.
 */
public class MailCacheCLI  {

    public static void main(String[] args) {

        CommandLineRunner.main(args, Arrays.asList(new MailCacheApp(),new PasswordManagerApp()));
    }
}