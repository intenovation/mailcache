package com.intenovation.mailcache.task;

import com.intenovation.appfw.task.BackgroundTask;
import com.intenovation.appfw.task.ProgressStatusCallback;
import com.intenovation.appfw.ui.UIService;
import com.intenovation.mailcache.CachedStore;
import com.intenovation.mailcache.MailCache;

import java.util.Collection;

public class ListStores extends BackgroundTask {
    public ListStores(UIService uiService) {
        super("list-stores", "lists all available mail stores");
        this.uiService=uiService;
    }
    UIService uiService;
    @Override
    public String execute(ProgressStatusCallback callback, Object parameter) throws InterruptedException {
        Collection<CachedStore> allStores = MailCache.getAllStores();
        String result="";
        for (CachedStore store : allStores){
            result+=store.getUsername();
            result+=" ";
            callback.update(50,store.getUsername());
            uiService.showInfo("All Stores",result);
        }
        return "Stores:"+result;
    }
}
