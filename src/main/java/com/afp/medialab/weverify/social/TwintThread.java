package com.afp.medialab.weverify.social;

import com.afp.medialab.weverify.social.dao.service.CollectService;
import com.afp.medialab.weverify.social.model.CollectRequest;
import com.afp.medialab.weverify.social.model.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;

public class TwintThread implements Runnable {

    private CollectService collectService;
    private static Logger Logger = LoggerFactory.getLogger(TwintThread.class);

    private CollectRequest request;
    private String name;

    public String getName() {
        return name;
    }

    public CollectRequest getRequest() {
        return request;
    }

    public TwintThread(CollectRequest request, String id, CollectService cs)
    {
        this.request = request;
        name = id;
        collectService = cs;
    }

    @Override
    public void run() {
        collectService.UpdateCollectStatus(name, Status.Running);
        try {

            String r = TwintRequestGenerator.generateRequest(request, name);
                    ProcessBuilder pb =
                            new ProcessBuilder("/bin/bash", "-c",
                                            "PATH=/usr/bin:/usr/local/bin:/bin; " + r);

                    Logger.info(r);
                    Process p = null;
                    try {
                        p = pb.start();

                        BufferedReader stdInput = new BufferedReader(new
                                InputStreamReader(p.getInputStream()));

                        BufferedReader stdError = new BufferedReader(new
                                InputStreamReader(p.getErrorStream()));

                        String s = "";

                        while ((s = stdError.readLine()) != null) {
                            Logger.error(s);
                        }

                        while ((s = stdInput.readLine()) != null) {
                            Logger.info(s);
                        }

                        stdInput.close();
                        stdError.close();

                        collectService.UpdateCollectStatus(name, Status.Done);
                    } catch (IOException e) {
                        e.printStackTrace();
                        collectService.UpdateCollectStatus(name, Status.Error);
                    }



        } catch (Exception e) {
            Logger.error(e.getMessage());
            collectService.UpdateCollectStatus(name, Status.Error);
            e.printStackTrace();
        }
    }
}
