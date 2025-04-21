package com.intenovation.mailcache;

import javax.mail.Message;
import javax.mail.MessagingException;
import java.util.Date;

public class MassageDirName {
    /**
     * Formats a message directory name based on date and subject
     * Format: YYYY-MM-DD_Subject
     */
    public static String formatMessageDirName(Message message) throws MessagingException {
        java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd");
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

}
