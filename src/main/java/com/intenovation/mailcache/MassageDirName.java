package com.intenovation.mailcache;

import javax.mail.Message;
import javax.mail.MessagingException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class MassageDirName {
    /**
     * Formats a message directory name based on date, time, and subject
     * Format: YYYY-MM-DD_HH-MM_Subject
     */
    public static String formatMessageDirName(Message message) throws MessagingException {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm");
        String prefix = "";

        // Get date and time
        Date sentDate = message.getSentDate();
        if (sentDate != null) {
            prefix = dateFormat.format(sentDate) + "_";
        } else {
            // Use current date and time if no sent date
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
}