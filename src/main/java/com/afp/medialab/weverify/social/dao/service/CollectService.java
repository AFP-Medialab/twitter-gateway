package com.afp.medialab.weverify.social.dao.service;

import com.afp.medialab.weverify.social.dao.entity.CollectHistory;
import com.afp.medialab.weverify.social.dao.repository.CollectInterface;
import com.afp.medialab.weverify.social.model.CollectRequest;
import com.afp.medialab.weverify.social.model.Status;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class CollectService {

    @Autowired
    CollectInterface collectInterface;

    public void SaveCollectInfo(Integer id, CollectRequest collectRequest, Date processStart, Date processEnd, Status status)
    {
        String query = "{\n" +
                            "\"search\" : " + collectRequest.getSearch() + ",\n" +
                            "\"from\" : " + collectRequest.getFrom() + ",\n" +
                            "\"until\" : " + collectRequest.getUntil() + "\n" +
                        "}";
        CollectHistory collectHistory = new CollectHistory(id, query, processStart, processEnd, status);
        collectInterface.save(collectHistory);
    }

    public Boolean UpdateCollectStatus(Integer id, Status status)
    {
        CollectHistory collectHistory = collectInterface.findCollectHistoryById(id);
        if (status == Status.Running && collectHistory.getStatus() == Status.NotStarted)
        {
            collectInterface.updateCollectProcessStart(id, new Date());
            collectInterface.updateCollectStatus(id, status);
            return true;
        }
        else if (status == Status.Done && collectHistory.getStatus() == Status.Running)
        {
            collectInterface.updateCollectProcessEnd(id, new Date());
            collectInterface.updateCollectStatus(id, status);
            return true;
        }
        else if (status == status.Error && collectHistory.getStatus() != status.Error)
        {
            collectInterface.updateCollectProcessEnd(id, new Date());
            collectInterface.updateCollectStatus(id, status);
            return true;
        }
        return false;
    }
}
