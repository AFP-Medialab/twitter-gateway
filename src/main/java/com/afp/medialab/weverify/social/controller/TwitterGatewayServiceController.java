package com.afp.medialab.weverify.social.controller;

import java.util.*;
import java.util.concurrent.ExecutionException;

import javax.validation.Valid;

import com.afp.medialab.weverify.social.dao.entity.Request;
import com.afp.medialab.weverify.social.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.BindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.afp.medialab.weverify.social.dao.entity.CollectHistory;
import com.afp.medialab.weverify.social.dao.service.CollectService;
import com.afp.medialab.weverify.social.twint.TwintThreadGroup;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

@RestController
@Api(value = "Twitter scraping API")
public class TwitterGatewayServiceController {

    private static Logger Logger = LoggerFactory.getLogger(TwitterGatewayServiceController.class);

    @Autowired
    private CollectService collectService;

    @Autowired
    private TwintThreadGroup ttg;

    @Value("${application.home.msg}")
    private String homeMsg;


    @RequestMapping(path = "/", method = RequestMethod.GET)
    public @ResponseBody
    String home() {
        return homeMsg;
    }


    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleResourceNotFoundException() {
        return "This session could not be found";
    }


    @ApiOperation(value = "Trigger a Twitter Scraping")
    @RequestMapping(path = "/collect", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody
    CollectResponse collect(@RequestBody @Valid CollectRequest collectRequest, BindingResult result) {

        if (!collectRequest.isValid())
            return new CollectResponse(null, Status.Error, "and_list or user_list must be given, from and until are mandatory", null);

        Logger.debug(result.getAllErrors().toString());
        if (result.hasErrors()) {
            String str = "";
            for (ObjectError r : result.getAllErrors()) {
                str += r.getDefaultMessage() + "; ";
            }
            Logger.info(str);
            return new CollectResponse(null, Status.Error, str, null);
        }

        Set<String> and_list = collectRequest.getKeywordList();
        Set<String> not_ist = collectRequest.getBannedWords();
        if (and_list != null)
            Logger.debug("and_list : " + and_list.toString());
        if (not_ist != null)
            Logger.debug("not_list : " + not_ist.toString());
        Logger.debug("from : " + collectRequest.getFrom().toString());
        Logger.debug("until : " + collectRequest.getUntil().toString());
        Logger.debug("language : " + collectRequest.getLang());
        Logger.debug("user : " + collectRequest.getUser_list());
        Logger.debug("verified : " + collectRequest.isVerified());
        Logger.debug("Retweets : " + collectRequest.getRetweetsHandling());
        Logger.debug("Media : " + collectRequest.getMedia());

        return useCache(collectRequest);
    }


    @ApiOperation(value = "Trigger a status check")
    @RequestMapping(path = "/status", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody
    StatusResponse status(@RequestBody StatusRequest statusRequest) {
        Logger.debug("POST status " + statusRequest.getSession());
        return getStatusResponse(statusRequest.getSession());
    }


    @ApiOperation(value = "Trigger a status check")
    @RequestMapping(path = "/status/{id}", method = RequestMethod.GET)
    public @ResponseBody
    StatusResponse status(@PathVariable("id") String id) {
        Logger.debug("GET status " + id);
        return getStatusResponse(id);
    }


    /**
     * @param session we want the status from.
     * @return StatusResponse of the session.
     * @func Returns the status response of a given session.
     */
    private StatusResponse getStatusResponse(String session) {
        CollectHistory collectHistory = collectService.getCollectInfo(session);
        if (collectHistory == null) throw new NotFoundException();

        CollectRequest collectRequest = new CollectRequest(collectHistory.getRequest());
        if (collectRequest != null) {
            if (collectHistory.getStatus() != Status.Done)
                return new StatusResponse(collectHistory.getSession(), collectHistory.getProcessStart(), collectHistory.getProcessEnd(),
                        collectHistory.getStatus(), collectRequest, null, null);
            else
                return new StatusResponse(collectHistory.getSession(), collectHistory.getProcessStart(), collectHistory.getProcessEnd(),
                        collectHistory.getStatus(), collectRequest, collectHistory.getCount(), collectHistory.getMessage());
        }
        return new StatusResponse(collectHistory.getSession(), null, null, Status.Error, null, null, "No query found, or parsing error");
    }


    public CollectResponse isContained(CollectRequest collectRequest) {
        Set<Request> keywords = collectService.isContainedKeywords(collectRequest);
        Set<Request> banned_words = collectService.isContainedBannedWords(collectRequest);
        Set<Request> users = collectService.isContainedUsers(collectRequest);

        Set<Request> maching_requests = new HashSet<Request>(keywords);
        maching_requests.retainAll(banned_words);
        maching_requests.retainAll(users);

        CollectResponse result = null;
        for (Request request : maching_requests) {
            if (result != null)
                return result;
            result = completingOldRequest(collectRequest, request);
        }
        return result;
    }

    /**
     * @param collectRequest collect request asked.
     * @return
     * @func Verifies if the request has already been done.
     * If not creates a new session and gives a CollectResponse accordingly.
     */
    private CollectResponse useCache(CollectRequest collectRequest) {
        // Check if this request is contained in a previous one
        CollectResponse alreadyDone = isContained(collectRequest);
        if (alreadyDone != null) {
            Logger.info("This request is contained in a already done request,  sessionId: " + alreadyDone.getSession());
            return alreadyDone;
        }

        // Creation of a brand new  CollectHistory
        String session = UUID.randomUUID().toString();
        CollectHistory collectHistory = collectService.saveCollectInfo(session, collectRequest, null, null, Status.Pending, null, null, 0, 0, 0);
        ttg.callTwintMultiThreaded(collectHistory, collectRequest);
        ;

        if (collectHistory.getStatus() != Status.Done)
            return new CollectResponse(session, collectHistory.getStatus(), null, collectHistory.getProcessEnd());
        return new CollectResponse(session, collectHistory.getStatus(), collectHistory.getMessage(), collectHistory.getProcessEnd());
    }

    /**
     * @param newCollectRequest
     * @param oldCollectRequest
     * @return corresponding collectResponse if their is a Mach, null otherwise.
     * @func If the newCollectRequest matches the searching criteria of oldCollectRequest.
     * It makes a Twint call for all the newCollectRequest days except the ones
     * that already have been searched by the oldCollectRequest.
     * if the 	newCollectRequest does not match,
     * It makes a new CollectHistory
     */
    private CollectResponse completingOldRequest(CollectRequest newCollectRequest, Request oldCollectRequest) {

        CollectRequest resultingCollectRequest = newCollectRequest;
        CollectHistory collectHistory = collectService.findCollectHistoryByRequest(oldCollectRequest);
        collectHistory.setMessage("Completing the research. This research has already been done from " + oldCollectRequest.getSince() + " to " + oldCollectRequest.getUntil());


        // The new request covers all the old request and more
        if (newCollectRequest.getFrom().compareTo(oldCollectRequest.getSince()) < 0
                && newCollectRequest.getUntil().compareTo(oldCollectRequest.getUntil()) > 0) {
            // Make a Twint collection from newCollectRequest.getFrom() to oldCollectRequest.getFrom()
            // And from  oldCollectRequest.getUntil() to newCollectRequest.getUntil()

            collectHistory.setStatus(Status.Pending);
            oldCollectRequest.setSince(newCollectRequest.getFrom());
            oldCollectRequest.setUntil(newCollectRequest.getUntil());
            collectService.save_collectHistory(collectHistory);
            collectService.save_request(oldCollectRequest);

            callTwintOnInterval(collectHistory, oldCollectRequest, newCollectRequest.getFrom(), oldCollectRequest.getSince());
            callTwintOnInterval(collectHistory, oldCollectRequest, oldCollectRequest.getUntil(), newCollectRequest.getUntil());
            return getCollectResponseFromTwintCall(collectHistory);
        }
        // THe new request all the odl request or less
        else if (newCollectRequest.getFrom().compareTo(oldCollectRequest.getSince()) >= 0
                && newCollectRequest.getUntil().compareTo(oldCollectRequest.getUntil()) <= 0) {
            // Return the existing research
            String messageDone = "This research has already been done.";
            return new CollectResponse(collectHistory);
        }
        // The new request covers before and a part of the old request
        else if (newCollectRequest.getFrom().compareTo(oldCollectRequest.getSince()) < 0
                && newCollectRequest.getUntil().compareTo(oldCollectRequest.getUntil()) <= 0
                && newCollectRequest.getUntil().compareTo(oldCollectRequest.getSince()) >= 0) {
            // make a search from newCollectRequest.getFrom to oldCollectRequest.getFrom()

            collectHistory.setStatus(Status.Pending);
            oldCollectRequest.setUntil(oldCollectRequest.getUntil());
            collectService.save_request(oldCollectRequest);
            collectService.save_collectHistory(collectHistory);
            callTwintOnInterval(collectHistory, oldCollectRequest, newCollectRequest.getFrom(), oldCollectRequest.getSince());
            return getCollectResponseFromTwintCall(collectHistory);

        }
        // The new request covers after and a part of the old request
        else if (newCollectRequest.getUntil().compareTo(oldCollectRequest.getUntil()) > 0
                && newCollectRequest.getFrom().compareTo(oldCollectRequest.getSince()) >= 0
                && newCollectRequest.getFrom().compareTo(oldCollectRequest.getUntil()) <= 0) {
            // make a search from oldCollectRequest.getUntil() to newCollectRequest.getUntil()

            collectHistory.setStatus(Status.Pending);
            oldCollectRequest.setUntil(oldCollectRequest.getSince());
            collectService.save_request(oldCollectRequest);
            collectService.save_collectHistory(collectHistory);
            callTwintOnInterval(collectHistory, oldCollectRequest, newCollectRequest.getUntil(), newCollectRequest.getUntil());
            return getCollectResponseFromTwintCall(collectHistory);
        }
        // else {
        // The new request covers no days in common with the old one
        // Undefined case yet so it's ignored for now.
        // This collect request will have a new session Id even thought it matched the research criteria.
        // }
        return null;
    }

    /**
     * @return the collect response corresponding to the new call to Twint.
     * @func getCollectResponseFromTwintCall
     */
    private CollectResponse getCollectResponseFromTwintCall(CollectHistory collectHistory) {
        if (collectHistory.getStatus() != Status.Done)
            return new CollectResponse(collectHistory);
        return new CollectResponse(collectHistory);
    }

    /**
     * @param from
     * @param until
     * @return
     * @func callTwintOnInterval overload with only one date interval.
     * call Twint on the interval and append the result to the elastic search session
     */
    private void callTwintOnInterval(CollectHistory collectHistory, Request request, Date from, Date until) {
        CollectRequest newCollectRequest = new CollectRequest(request);
        newCollectRequest.setFrom(from);
        newCollectRequest.setUntil(until);
        ttg.callTwintMultiThreaded(collectHistory, newCollectRequest);
    }


    @RequestMapping(value = "/collect-history", method = RequestMethod.GET)
    public @ResponseBody
    HistoryResponse collectHistory(@RequestParam(value = "limit", required = false, defaultValue = "5") int limit,
                                   @RequestParam(value = "asc", required = false, defaultValue = "false") boolean asc,
                                   @RequestParam(value = "desc", required = false, defaultValue = "false") boolean desc,
                                   @RequestParam(value = "status", required = false) String status) {
        List<CollectHistory> last;

        Logger.info("GET collect-history :  " + status);

        if (status == null) {
            if (!asc && !desc)
                last = collectService.getLasts(limit, true);
            else if (asc)
                last = collectService.getLasts(limit, !asc);
            else
                last = collectService.getLasts(limit, desc);
        } else {
            if (!asc && !desc)
                last = collectService.getByStatus(status, limit, true);
            else if (asc)
                last = collectService.getByStatus(status, limit, !asc);
            else
                last = collectService.getByStatus(status, limit, desc);


        }
        return new HistoryResponse(last);
    }

    @ApiOperation(value = "Get the requests history")
    @RequestMapping(path = "/collect-history", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody
    HistoryResponse status(@RequestBody @Valid HistoryRequest historyRequest) {
        Logger.info("POST collect-history : " + historyRequest.toString());
        List<CollectHistory> collectHistoryList = collectService.getHistory(historyRequest.getLimit(), historyRequest.getStatus(),
                (historyRequest.getSort() == null ? false : historyRequest.getSort().equals("desc")),
                historyRequest.getProcessStart(), historyRequest.getProcessTo());
        return new HistoryResponse(collectHistoryList);
    }

    @ApiOperation(value = "Update an old request")
    @RequestMapping(path = "/collect-update/{id}", method = RequestMethod.GET)
    public @ResponseBody
    StatusResponse collectUpdate(@PathVariable("id") String id) throws ExecutionException, InterruptedException {
        return collectUpdateFunction(id);
    }

    @ApiOperation(value = "Update an old request")
    @RequestMapping(path = "/collect-update", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody
    StatusResponse collectUpdate(@RequestBody @Valid CollectUpdateRequest collectUpdateRequest) throws ExecutionException, InterruptedException {
        String id = collectUpdateRequest.getSession();
        return collectUpdateFunction(id);
    }

    /**
     * @param session
     * @return Status response or the corresponding session
     * @throws ExecutionException
     * @throws InterruptedException
     * @func Calls Twint on the time interval of a session. The result replaces the old ones un elastic search.
     */
    private StatusResponse collectUpdateFunction(String session) throws ExecutionException, InterruptedException {
        Logger.info("Collect-Update " + session);

        CollectHistory collectHistory = collectService.getCollectInfo(session);
        if (collectHistory == null) throw new NotFoundException();


        if (collectHistory.getStatus() != Status.Done) {
            StatusResponse res = getStatusResponse(session);
            res.setMessage("This session is already updating");
            return res;
        }

        collectHistory.setStatus(Status.Pending);
        Request request = collectHistory.getRequest();
        if (request == null){
            collectHistory.setStatus(Status.Error);
            collectHistory.setMessage("Could not find the Request associated");
            collectService.save_collectHistory(collectHistory);
            throw new NotFoundException();
        }
        collectHistory.setProcessStart(new Date());
        collectService.save_collectHistory(collectHistory);
        callTwintOnInterval(collectHistory, request, request.getSince(), request.getUntil());
        return getStatusResponse(session);
    }
}