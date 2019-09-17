package com.afp.medialab.weverify.social.dao.service;

import java.io.IOException;
import java.util.Date;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.afp.medialab.weverify.social.twint.TwintThreadExecutor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.afp.medialab.weverify.social.dao.entity.CollectHistory;
import com.afp.medialab.weverify.social.dao.repository.CollectInterface;
import com.afp.medialab.weverify.social.model.CollectRequest;
import com.afp.medialab.weverify.social.model.CollectResponse;
import com.afp.medialab.weverify.social.model.Status;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;

@Service
public class CollectService {

    private static org.slf4j.Logger Logger = LoggerFactory.getLogger(TwintThreadExecutor.class);
    @Autowired
    CollectInterface collectInterface;


    public String CollectRequestToString(CollectRequest collectRequest) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.writeValueAsString(collectRequest);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return "Json parsing Error";
    }

    public CollectRequest StringToCollectRequest(String query) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            CollectRequest collectRequest = mapper.readValue(query, CollectRequest.class);
            return collectRequest;
        } catch (JsonParseException e) {
            e.printStackTrace();
        } catch (JsonMappingException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void SaveCollectInfo(String session, CollectRequest collectRequest, Date processStart, Date processEnd, Status status) {
        CollectHistory collectHistory = new CollectHistory(session, CollectRequestToString(collectRequest), processStart, processEnd, status);
        collectInterface.save(collectHistory);
    }

    public CollectResponse alreadyExists(CollectRequest collectRequest) {
        CollectHistory collectHistory = collectInterface.findCollectHistoryByQuery(CollectRequestToString(collectRequest));
        if (collectHistory == null)
            return null;
        return new CollectResponse(collectHistory.getSession(), collectHistory.getStatus(), null, collectHistory.getProcessEnd());
    }

    public Boolean updateCollectStatus(String session, Status newStatus) {
        CollectHistory collectHistory = collectInterface.findCollectHistoryBySession(session);
        Status existingStatus = collectHistory.getStatus();
        if (newStatus == Status.Pending && existingStatus == Status.Done) {
            collectInterface.updateCollectProcessEnd(session, null);
            collectInterface.updateCollectStatus(session, newStatus.toString());
            Status status = collectInterface.findCollectHistoryBySession(session).getStatus();
            return true;
        }
        if (newStatus == Status.Running && existingStatus == Status.Pending) {
            if (collectHistory.getProcessStart() == null)
                collectInterface.updateCollectProcessStart(session, new Date());
            collectInterface.updateCollectStatus(session, newStatus.toString());
            return true;
        } else if (newStatus == Status.Done && existingStatus == Status.Running) {
            collectInterface.updateCollectProcessEnd(session, new Date());
            collectInterface.updateCollectStatus(session, newStatus.toString());
            return true;
        } else if (newStatus == Status.Error && existingStatus != Status.Error) {
            collectInterface.updateCollectProcessEnd(session, new Date());
            collectInterface.updateCollectStatus(session, newStatus.toString());
            return true;
        }
        return false;
    }

    public void updateCollectQuery(String session, CollectRequest collectRequest) {
        String query = CollectRequestToString(collectRequest);
        collectInterface.updateCollectQuery(session, query);
    }

    public void updateCollectProcessEnd(String session, Date date) {
        collectInterface.updateCollectProcessEnd(session, date);
    }

    public void updateCollectProcessStart(String session, Date date) {
        collectInterface.updateCollectProcessStart(session, date);
    }

    public CollectHistory getCollectInfo(String session) {
        return collectInterface.findCollectHistoryBySession(session);
    }

    public List<CollectHistory> getLasts(int nb) {
        List<CollectHistory> collectHistoryList = collectInterface.findAll();
        Collections.reverse(collectHistoryList);

        if (collectHistoryList.size() >= nb)
            collectHistoryList = collectHistoryList.subList(0, nb);
        return collectHistoryList;
    }

    public List<CollectHistory> getAll(boolean desc) {
        List<CollectHistory> collectHistoryList = collectInterface.findAll();
        if (desc)
            Collections.reverse(collectHistoryList);

        return collectHistoryList;
    }

    public List<CollectHistory> getByStatus(String status) {
        List<CollectHistory> collectHistoryList = collectInterface.findCollectHistoryByStatus(status);
        return collectHistoryList;
    }

    public List<CollectHistory> getHistory(int limit, String status, boolean desc, Date processStart, Date processEnd) {
        List<CollectHistory> collectHistoryList = null;
        if (status != null && processEnd != null && processStart != null)
            collectHistoryList = collectInterface.findCollectHistoryByProcessEndLessThanEqualOrProcessEndIsNullAndProcessStartGreaterThanEqualAndStatus(processEnd, processStart, status);
        else if (status != null && processEnd == null && processStart == null)
            collectHistoryList = collectInterface.findCollectHistoryByStatus(status);
        else if (status != null && processEnd != null)
            collectHistoryList = collectInterface.findCollectHistoryByStatusAndProcessEndLessThan(status, processEnd);
        else if (status != null)
            collectHistoryList = collectInterface.findCollectHistoryByStatusAndProcessStartGreaterThan(status, processStart);
        else if (processEnd != null)
            collectHistoryList = collectInterface.findCollectHistoryByProcessEndLessThan(processEnd);
        else if (processStart != null)
            collectHistoryList = collectInterface.findCollectHistoryByProcessStartGreaterThan(processStart);
        else {

            collectHistoryList = collectInterface.findAll();

        }
        if (desc)
            Collections.reverse(collectHistoryList);

        if (limit != 0 && collectHistoryList.size() > limit)
            collectHistoryList = collectHistoryList.subList(0, limit);
        return collectHistoryList;
    }

    public void UpdateCollectMessage(String session, String message) {
        collectInterface.updateCollectMessage(session, message);
    }

    public Set<CollectHistory> findCollectHistoryByQueryContains(String str) {
        return collectInterface.findCollectHistoryByQueryContains(str);
    }
}

