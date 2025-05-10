package com.intenovation.mailcache.task;

import com.intenovation.appfw.task.ProgressStatusCallback;
import com.intenovation.mailcache.CachedMessage;

import javax.mail.MessagingException;

/**
 * Task that reads a message and displays its basic information.
 */
public class ReadMessageTask extends AbstractMessageTask {

    public ReadMessageTask() {
        super("read", "Reads message subject, sender, and date");
    }

    @Override
    protected String executeOnMessage(ProgressStatusCallback callback, CachedMessage message) throws InterruptedException {
        String subject = null;
        String from = null;
        String date = null;

        try {
            subject = message.getSubject();
            from = ""+message.getCleanFrom();
            date = ""+message.getSentDate();
        } catch (MessagingException e) {
            throw new RuntimeException(e);
        }
        callback.update(50, subject);
        return date +"\t"+ from+"\t"+subject;
    }
}