package com.intenovation.mailcache.task;

import com.intenovation.appfw.task.ProgressStatusCallback;
import com.intenovation.mailcache.CachedFolder;
import com.intenovation.mailcache.CachedMessage;

import javax.mail.Message;
import javax.mail.MessagingException;

/**
 * Task that reads a message.
 */
public class ReadMessage extends AbstractMessageTask {

    public ReadMessage() {
        super("read", "reads message subject");
    }
    

    @Override
    protected String executeOnMessage(ProgressStatusCallback callback, CachedMessage message) throws InterruptedException {
        String subject = null;
        try {
            subject = message.getSubject();
        } catch (MessagingException e) {
            throw new RuntimeException(e);
        }
        callback.update(50,subject);
        return "";
    }
}