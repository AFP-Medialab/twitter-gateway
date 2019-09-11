package com.afp.medialab.weverify.social.controller;

import java.io.IOException;
import java.util.UUID;

import com.afp.medialab.weverify.social.TwintCall;
import com.afp.medialab.weverify.social.TwintThread;
import com.afp.medialab.weverify.social.dao.entity.CollectHistory;
import com.afp.medialab.weverify.social.dao.service.CollectService;
import com.afp.medialab.weverify.social.model.*;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import com.afp.medialab.weverify.social.model.CollectRequest;
import com.afp.medialab.weverify.social.model.CollectResponse;
import com.afp.medialab.weverify.social.model.Status;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

@RestController
@Api(value = "Twitter scraping API")
public class TwitterGatewayServiceController {

	private static Logger Logger = LoggerFactory.getLogger(TwitterGatewayServiceController.class);

	@Autowired
	private TwintCall tc;

	@Autowired
	private CollectService collectService;

	@Value("${application.home.msg}")
	private String homeMsg;

	@RequestMapping(path = "/", method = RequestMethod.GET)
	public @ResponseBody String home() {
		return homeMsg;
	}


	@ApiOperation(value = "Trigger a Twitter Scraping")
	@RequestMapping(path = "/collect", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public @ResponseBody CollectResponse collect(@RequestBody CollectRequest collectRequest) {
		Logger.info(collectRequest.getSearch());
		Logger.info(collectRequest.getFrom().toString());
		Logger.info(collectRequest.getUntil().toString());
		Logger.info(collectRequest.getLang());
		Logger.info(collectRequest.getUser());

		// Check if this request has already been donne, if it does return it
		CollectResponse alreadyDonne = collectService.alreadyExists(collectRequest);
		if (alreadyDonne != null) {
			Logger.info("This request has already been donne sessionId: " + alreadyDonne.getSession());
			return alreadyDonne;
		}

		String session = UUID.randomUUID().toString();
		Status s = tc.collect(new TwintThread(collectRequest, session, collectService));
		return new CollectResponse(session, s);
	}

	@ApiOperation(value = "Trigger a status check")
	@RequestMapping(path = "/status", method = RequestMethod.POST, consumes = "application/json", produces = "application/json")
	public @ResponseBody StatusResponse status(@RequestBody StatusRequest statusRequest) {
		Logger.info(statusRequest.getSession());

		CollectHistory collectHistory = collectService.getCollectInfo(statusRequest.getSession());
		if (collectHistory == null)
			return new StatusResponse(statusRequest.getSession(), null, null, Status.Error, null);

		ObjectMapper mapper = new ObjectMapper();
		try {
			CollectRequest collectRequest = mapper.readValue(collectHistory.getQuery(), CollectRequest.class);
			return new StatusResponse(collectHistory.getSession(), collectHistory.getProcessStart(), collectHistory.getProcessEnd(),
					collectHistory.getStatus(), collectRequest);
		} catch (JsonParseException e) {
			e.printStackTrace();
		} catch (JsonMappingException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return new StatusResponse(collectHistory.getSession(), null, null, Status.Error, null);
	}
}
