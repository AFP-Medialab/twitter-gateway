package com.afp.medialab.weverify.social.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import com.afp.medialab.weverify.social.security.model.FusionLoginRequest;
import com.afp.medialab.weverify.social.security.model.FusionLoginResponse;
import com.afp.medialab.weverify.social.security.model.JwtRequest;

@RestController
@CrossOrigin
public class JwtAuthenticationController {

	@Value("${fusionAuth.clientId}")
	private String fusionClientId;
	
	@Value("${fusionAuth.loginUri}")
	private String fusionUri;

	@RequestMapping(value = "/authenticate", method = RequestMethod.POST)
	public FusionLoginResponse createAuthenticationToken(@RequestBody JwtRequest authenticationRequest)
			throws Exception {

		FusionLoginRequest fusionLoginRequest = new FusionLoginRequest();
		fusionLoginRequest.setLoginId(authenticationRequest.getUsername());
		fusionLoginRequest.setPassword(authenticationRequest.getPassword());
		fusionLoginRequest.setApplicationId(fusionClientId);

		FusionLoginResponse response = new RestTemplate().postForObject(fusionUri, fusionLoginRequest,
				FusionLoginResponse.class);

		return response;
	}
}
