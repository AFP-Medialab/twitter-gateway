package com.afp.medialab.weverify.social.security;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.afp.medialab.weverify.social.security.model.FusionAuthDataConverter;
import com.afp.medialab.weverify.social.security.model.JwtCreateAccessCodeRequest;
import com.afp.medialab.weverify.social.security.model.JwtCreateUserRequest;
import com.afp.medialab.weverify.social.security.model.JwtLoginRequest;
import com.afp.medialab.weverify.social.security.model.JwtLoginResponse;
import com.afp.medialab.weverify.social.security.model.JwtRefreshTokenRequest;
import com.afp.medialab.weverify.social.security.model.JwtRefreshTokenResponse;
import com.inversoft.error.Errors;
import com.inversoft.rest.ClientResponse;

import io.fusionauth.client.FusionAuthClient;
import io.fusionauth.domain.GroupMember;
import io.fusionauth.domain.User;
import io.fusionauth.domain.UserRegistration;
import io.fusionauth.domain.api.LoginResponse;
import io.fusionauth.domain.api.MemberRequest;
import io.fusionauth.domain.api.MemberResponse;
import io.fusionauth.domain.api.UserRequest;
import io.fusionauth.domain.api.UserResponse;
import io.fusionauth.domain.api.jwt.RefreshRequest;
import io.fusionauth.domain.api.jwt.RefreshResponse;
import io.fusionauth.domain.api.passwordless.PasswordlessLoginRequest;
import io.fusionauth.domain.api.passwordless.PasswordlessSendRequest;
import io.fusionauth.domain.api.passwordless.PasswordlessStartRequest;
import io.fusionauth.domain.api.passwordless.PasswordlessStartResponse;
import io.fusionauth.domain.jwt.DeviceInfo;
import io.fusionauth.domain.jwt.RefreshToken;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

/**
 * Authentication controller, based on JWT and providing passwordless
 * authentication.
 * 
 * @author Bertrand GOUPIL
 * @author <a href="mailto:eric@rickspirit.io">Eric SCHAEFFER</a>
 */
@RestController
@CrossOrigin
@RequestMapping(path = "/api/v1/auth")
@Api(description = "User authentication API.")
public class JwtAuthenticationController {

	private static Logger Logger = LoggerFactory.getLogger(JwtAuthenticationController.class);

	@Value("${security.fusionAuth.url}")
	private String fusionAuthUrl;

	@Value("${security.fusionAuth.apiKey}")
	private String fusionAuthApiKey;

	@Value("${security.auth.create-user.groupId}")
	private UUID createUserGroupId;

	@Value("${security.auth.twint.applicationId}")
	private UUID twintApplicationId;

	private FusionAuthClient fusionAuthClient;

	/**
	 * Constructor.
	 */
	public JwtAuthenticationController() {
		super();
	}

	/**
	 * Register a new user API.
	 * 
	 * @param createUserRequest
	 */
	@RequestMapping(path = "/user", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
	@ResponseStatus(HttpStatus.OK)
	@ApiOperation(value = "Create a new user registration request on the system.", nickname = "registerUser",
			notes = "Create a user registration request on the system, subject to moderation."
					+ " Once validated, user will be notified by email and will be able to request an authentication code."
					+ "\n" + "The operation will not return any error in case of invalid data or request.")
	@ApiResponses(
			value = { @ApiResponse(code = 200, message = "User registration request has been received correctly."),
					@ApiResponse(code = 400, message = "Request is incomplete or malformed."),
					@ApiResponse(code = 500, message = "An internal error occured during request processing.") })
	public void createUser(@Valid @RequestBody JwtCreateUserRequest createUserRequest) {
		Logger.debug("Create User with request {}", createUserRequest);

		String userEmail = createUserRequest.email;

		// Check if user already exists
		Logger.debug("Looking for user {}", userEmail);
		ClientResponse<UserResponse, Errors> getUserResponse = getFusionAuthClient().retrieveUserByEmail(userEmail);
		if (getUserResponse.wasSuccessful()) {
			// User already exists, silently returning
			Logger.info("Duplicate user {} request, silently returning", userEmail);
			return;
		}

		// New User to create
		User user = new User();
		user.email = userEmail;
		// TODO: generate random strong password
		user.password = "PASSWORD";
		user.firstName = createUserRequest.firstName;
		user.lastName = createUserRequest.lastName;
		Map<String, Object> userData = new HashMap<String, Object>();
		Optional.ofNullable(createUserRequest.company).ifPresent(s -> userData.put("company", s));
		Optional.ofNullable(createUserRequest.position).ifPresent(s -> userData.put("position", s));
		user.data = userData;
		Optional.ofNullable(createUserRequest.preferredLanguages).map(l -> user.preferredLanguages.addAll(l));
		user.timezone = createUserRequest.timezone;

		// Create user
		Logger.debug("Creating user {} with data: {}", userEmail, user);
		ClientResponse<UserResponse, Errors> createUserResponse = getFusionAuthClient().createUser(null,
				new UserRequest(false, false, user));

		if (!createUserResponse.wasSuccessful()) {
			// Stop and return error message
			Logger.warn("Service error creating new user {}: {}", userEmail, formatClientResponse(createUserResponse));
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
					"Service was unable to process your request");
		}

		UserResponse userResponse = createUserResponse.successResponse;
		UUID userId = userResponse.user.id;

		// Add user to created users group
		UUID userGroupUUID = this.createUserGroupId;
		GroupMember groupMember = new GroupMember();
		groupMember.groupId = userGroupUUID;
		groupMember.userId = userId;
		MemberRequest memberRequest = new MemberRequest(userGroupUUID, Collections.singletonList(groupMember));
		Logger.debug("Adding user {} to created users group with data: {}", userEmail, groupMember);
		ClientResponse<MemberResponse, Errors> createGroupMembersResponse = getFusionAuthClient()
				.createGroupMembers(memberRequest);

		if (!createGroupMembersResponse.wasSuccessful()) {
			Logger.warn("Service error adding user {} to created users group: {}", userEmail,
					formatClientResponse(createGroupMembersResponse));
		}

		// To enforce security, deactivate user account
		Logger.debug("Deactivating user {}", userEmail);
		ClientResponse<Void, Errors> deactivateUserResponse = getFusionAuthClient().deactivateUser(userId);

		if (!deactivateUserResponse.wasSuccessful()) {
			Logger.warn("Service error deactivating user {}: {}", userEmail,
					formatClientResponse(deactivateUserResponse));
		}

		return;
	}

	/**
	 * Request application access code API.
	 * 
	 * @param createAccessCodeRequest
	 */
	@RequestMapping(path = "/accesscode", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
	@ResponseStatus(HttpStatus.OK)
	@ApiOperation(value = "Request an access code for user authentication (sent by email).")
	@ApiResponses(value = { @ApiResponse(code = 200, message = "Access code request has been received correctly."),
			@ApiResponse(code = 400, message = "Request is incomplete or malformed."),
			@ApiResponse(code = 500, message = "An internal error occured during request processing.") })
	public void createAccessCode(@Valid @RequestBody JwtCreateAccessCodeRequest createAccessCodeRequest) {
		Logger.debug("Create access code with request {}", createAccessCodeRequest);

		String userEmail = createAccessCodeRequest.email;

		// Check if user exists
		Logger.debug("Looking for user {}", userEmail);
		ClientResponse<UserResponse, Errors> getUserResponse = getFusionAuthClient().retrieveUserByEmail(userEmail);
		if (!getUserResponse.wasSuccessful()) {
			if (getUserResponse.status == 404) {
				// User not found
				Logger.debug("User {} not found", userEmail);
				// throw new ServiceBadRequestException("Unable to process request for provided
				// email");
				return;
			}
			// Error in service call
			Logger.warn("Service error getting user {}: {}", userEmail, formatClientResponse(getUserResponse));
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
					"Service was unable to process your request");
		}

		// Check user can log (active, has registration/group for application
		// access)
		User user = getUserResponse.successResponse.user;
		if (!user.active) {
			// User is deactivated
			Logger.debug("User {} is deactivated", userEmail);
			return;
		}
		// Check registration/group for application access
		Optional<UserRegistration> userRegistrationOpt = Optional
				.ofNullable(user.getRegistrationForApplication(this.twintApplicationId));
		if (!userRegistrationOpt.isPresent()) {
			// User doesn't have access rights to application
			Logger.debug("User {} doesn't have a registration for application {}", userEmail, this.twintApplicationId);
			return;
		}

		// Creating authentication access code
		Logger.debug("Creating authentication access code for user {}", userEmail);
		PasswordlessStartRequest pwdStartRequest = new PasswordlessStartRequest();
		pwdStartRequest.applicationId = this.twintApplicationId;
		pwdStartRequest.loginId = userEmail;

		ClientResponse<PasswordlessStartResponse, Errors> pwdStartResponse = getFusionAuthClient()
				.startPasswordlessLogin(pwdStartRequest);
		if (!pwdStartResponse.wasSuccessful()) {
			Logger.warn("Service error creating authentication access code for user {}: {}", userEmail,
					formatClientResponse(pwdStartResponse));
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
					"Service was unable to process your request");
		}

		// Send authentication access code to user (email)
		Logger.debug("Sending authentication access code for user {}", userEmail);
		PasswordlessSendRequest pwdSendRequest = new PasswordlessSendRequest();
		pwdSendRequest.code = pwdStartResponse.successResponse.code;
		ClientResponse<Void, Errors> pwdSendResponse = getFusionAuthClient().sendPasswordlessCode(pwdSendRequest);
		if (!pwdSendResponse.wasSuccessful()) {
			Logger.warn("Service error sending authentication access code for user {}: {}", userEmail,
					formatClientResponse(pwdSendResponse));
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
					"Service was unable to process your request");
		}

		return;
	}

	/**
	 * @param loginRequest
	 * @param httpRequest
	 * @return
	 */
	@RequestMapping(path = "/login", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseStatus(HttpStatus.OK)
	@ApiOperation(value = "Login a user using an access code.")
	@ApiResponses(value = { @ApiResponse(code = 200, message = "Login has been successful."),
			@ApiResponse(code = 400, message = "Request is incomplete or malformed."),
			@ApiResponse(code = 403, message = "Invalid credentials, login has been refused."),
			@ApiResponse(code = 409, message = "User account state is preventing login."),
			@ApiResponse(code = 410, message = "User account is expired."),
			@ApiResponse(code = 500, message = "An internal error occured during request processing.") })
	public JwtLoginResponse login(@Valid @RequestBody JwtLoginRequest loginRequest, HttpServletRequest httpRequest) {
		Logger.debug("Login with request {}", loginRequest);

		PasswordlessLoginRequest pwdLessLoginRequest = new PasswordlessLoginRequest();
		pwdLessLoginRequest.applicationId = this.twintApplicationId;
		pwdLessLoginRequest.code = loginRequest.code;
		pwdLessLoginRequest.ipAddress = HttpRequestUtils.getClientIpAddress();
		Map<String, String> userAgentInfo = HttpRequestUtils.getUserAgentInformation();
		if (!userAgentInfo.isEmpty()) {
			pwdLessLoginRequest.metaData = new RefreshToken.MetaData();
			pwdLessLoginRequest.metaData.device = new DeviceInfo();
			pwdLessLoginRequest.metaData.device.name = userAgentInfo.get("DeviceName");
			String deviceTypeStr = userAgentInfo.get("DeviceClass");
			DeviceInfo.DeviceType deviceType = null;
			if (deviceTypeStr != null) {
				if ("Desktop".equalsIgnoreCase(deviceTypeStr)) {
					deviceType = DeviceInfo.DeviceType.DESKTOP;
				} else if ("Anonymized".equalsIgnoreCase(deviceTypeStr)) {
					deviceType = DeviceInfo.DeviceType.UNKNOWN;
				} else if ("Unknown".equalsIgnoreCase(deviceTypeStr)) {
					deviceType = DeviceInfo.DeviceType.UNKNOWN;
				} else if ("Mobile".equalsIgnoreCase(deviceTypeStr)) {
					deviceType = DeviceInfo.DeviceType.MOBILE;
				} else if ("Tablet".equalsIgnoreCase(deviceTypeStr)) {
					deviceType = DeviceInfo.DeviceType.TABLET;
				} else if ("Phone".equalsIgnoreCase(deviceTypeStr)) {
					deviceType = DeviceInfo.DeviceType.MOBILE;
				} else if ("Watch".equalsIgnoreCase(deviceTypeStr)) {
					deviceType = DeviceInfo.DeviceType.MOBILE;
				} else if ("Virtual Reality".equalsIgnoreCase(deviceTypeStr)) {
					deviceType = DeviceInfo.DeviceType.OTHER;
				} else if ("eReader".equalsIgnoreCase(deviceTypeStr)) {
					deviceType = DeviceInfo.DeviceType.MOBILE;
				} else if ("Set-top box".equalsIgnoreCase(deviceTypeStr)) {
					deviceType = DeviceInfo.DeviceType.TV;
				} else if ("TV".equalsIgnoreCase(deviceTypeStr)) {
					deviceType = DeviceInfo.DeviceType.TV;
				} else if ("Game Console".equalsIgnoreCase(deviceTypeStr)) {
					deviceType = DeviceInfo.DeviceType.TV;
				} else if ("Handheld Game Console".equalsIgnoreCase(deviceTypeStr)) {
					deviceType = DeviceInfo.DeviceType.TV;
				} else if ("Voice".equalsIgnoreCase(deviceTypeStr)) {
					deviceType = DeviceInfo.DeviceType.OTHER;
				} else if ("Robot".equalsIgnoreCase(deviceTypeStr)) {
					deviceType = DeviceInfo.DeviceType.SERVER;
				} else if ("Robot Mobile".equalsIgnoreCase(deviceTypeStr)) {
					deviceType = DeviceInfo.DeviceType.SERVER;
				} else if ("Robot Imitator".equalsIgnoreCase(deviceTypeStr)) {
					deviceType = DeviceInfo.DeviceType.SERVER;
				} else if ("Hacker".equalsIgnoreCase(deviceTypeStr)) {
					deviceType = DeviceInfo.DeviceType.OTHER;
				} else {
					deviceType = DeviceInfo.DeviceType.UNKNOWN;
				}
			}
			pwdLessLoginRequest.metaData.device.type = deviceType;
			// TODO: better description...
			pwdLessLoginRequest.metaData.device.description = userAgentInfo.get("Useragent");
		}
		Logger.debug("Login user with information: {}", pwdLessLoginRequest);
		ClientResponse<LoginResponse, Errors> pwdLessLoginResponse = getFusionAuthClient()
				.passwordlessLogin(pwdLessLoginRequest);

		if (!pwdLessLoginResponse.wasSuccessful()) {
			if (pwdLessLoginResponse.status >= 500) {
				Logger.warn("FusionAuth error: {}", formatClientResponse(pwdLessLoginResponse));
				throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
						"Service was unable to process your request");
				// throw new ServiceInternalException("Service was unable to process your
				// request", pwdLessLoginResponse);
			}
			if (pwdLessLoginResponse.status == 400) {
				Logger.warn("Error while calling FusionAuth service: {}", formatClientResponse(pwdLessLoginResponse));
				throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
						"Service was unable to process your request");
				// throw new ServiceInternalException("Service was unable to process your
				// request", pwdLessLoginResponse);
			}
			if (pwdLessLoginResponse.status == 409) {
				// User blocked
				throw new ResponseStatusException(HttpStatus.CONFLICT, "User account is blocked");
			}
			if (pwdLessLoginResponse.status == 410) {
				// User expired
				throw new ResponseStatusException(HttpStatus.GONE, "User account is expired");
			}
			// Return 403
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid credentials");
		}

		// TODO: logout user before returning?
		LoginResponse respContent = pwdLessLoginResponse.successResponse;
		if ((respContent.token == null) || (respContent.user == null)) {
			// User state prevent login
			throw new ResponseStatusException(HttpStatus.CONFLICT, "User account is blocked");
		}
		if (!respContent.user.active) {
			// User inactive
			throw new ResponseStatusException(HttpStatus.CONFLICT, "User account is blocked");
		}
		if (respContent.user.getRegistrationForApplication(this.twintApplicationId) == null) {
			// User not registered for application
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid credentials");
		}

		// Convert response data
		JwtLoginResponse response = new JwtLoginResponse();
		response.token = respContent.token;
		response.refreshToken = respContent.refreshToken;
		response.user = FusionAuthDataConverter.toJwtUser(respContent.user, this.twintApplicationId);

		return response;
	}

	/**
	 * @param refreshTokenRequest
	 * @return
	 */
	@RequestMapping(path = "/refreshtoken", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseStatus(HttpStatus.OK)
	@ApiOperation(value = "Refresh a user access token.")
	@ApiResponses(value = { @ApiResponse(code = 200, message = "Refresh has been successful."),
			@ApiResponse(code = 400, message = "Request is incomplete or malformed."),
			@ApiResponse(code = 401,
					message = "Invalid or expired refresh token, refresh has been refused, user is logged out."),
			@ApiResponse(code = 500, message = "An internal error occured during request processing.") })
	public JwtRefreshTokenResponse refreshToken(@Valid @RequestBody JwtRefreshTokenRequest refreshTokenRequest) {
		Logger.debug("Refresh token with request {}", refreshTokenRequest);

		RefreshRequest refreshRequest = new RefreshRequest();
		refreshRequest.refreshToken = refreshTokenRequest.refreshToken;

		ClientResponse<RefreshResponse, Errors> refreshClientResponse = getFusionAuthClient()
				.exchangeRefreshTokenForJWT(refreshRequest);

		if (!refreshClientResponse.wasSuccessful()) {
			if (refreshClientResponse.status >= 500) {
				throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
						"Service was unable to process your request");
			}
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
		}

		JwtRefreshTokenResponse refreshTokenResponse = new JwtRefreshTokenResponse();
		refreshTokenResponse.token = refreshClientResponse.successResponse.token;

		return refreshTokenResponse;
	}

	@RequestMapping(path = "/logout", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
	public Object logout(Object logoutRequest) throws Exception {
		// TODO
		return null;
	}

	/**
	 * Validation exception handler method.
	 * 
	 * @param request
	 * @param exception
	 * @return
	 */
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public Map<String, Object> handleValidationExceptions(HttpServletRequest request,
			MethodArgumentNotValidException exception) {
		Map<String, Object> respBody = new HashMap<>();

		respBody.put("timestamp", Instant.now().toString());
		respBody.put("status", HttpStatus.BAD_REQUEST.value());
		respBody.put("path", request.getRequestURI());

		BindingResult bindingResult = exception.getBindingResult();
		List<Map<String, Object>> errors = new ArrayList<Map<String, Object>>();
		bindingResult.getAllErrors().forEach((err) -> {
			Map<String, Object> error = new HashMap<String, Object>();
			error.put("error", (err instanceof FieldError) ? ((FieldError) err).getField() : err.getObjectName());
			error.put("message", err.getDefaultMessage());
			errors.add(error);
		});
		if (errors.size() > 1) {
			respBody.put("errors", errors.toArray());
		} else {
			respBody.putAll(errors.get(0));
		}

		return respBody;
	}

	// @RequestMapping(path = "/authenticate", method = RequestMethod.POST)
	// public FusionLoginResponse createAuthenticationToken(@RequestBody JwtRequest
	// authenticationRequest)
	// throws Exception {
	//
	// FusionLoginRequest fusionLoginRequest = new FusionLoginRequest();
	// fusionLoginRequest.setLoginId(authenticationRequest.getUsername());
	// fusionLoginRequest.setPassword(authenticationRequest.getPassword());
	// fusionLoginRequest.setApplicationId(fusionClientId);
	//
	// FusionLoginResponse response = new RestTemplate().postForObject(fusionUri,
	// fusionLoginRequest,
	// FusionLoginResponse.class);
	//
	// return response;
	// }

	/**
	 * @return the fusionAuthClient
	 */
	private FusionAuthClient getFusionAuthClient() {
		initFusionAuthClient();
		return this.fusionAuthClient;
	}

	/**
	 * Check and initialize if required the FusionAuth java client.
	 */
	private void initFusionAuthClient() {
		// Init FusionAuth client
		if (this.fusionAuthClient == null) {
			this.fusionAuthClient = new FusionAuthClient(this.fusionAuthApiKey, this.fusionAuthUrl);
		}
	}

	/**
	 * Format a ClientResponse object for logging.
	 * 
	 * @param response
	 * @return
	 */
	private String formatClientResponse(ClientResponse<?, Errors> response) {
		StringBuffer strBuffer = new StringBuffer();
		strBuffer.append("url ").append(String.valueOf(response.url));
		strBuffer.append(", ").append("status ").append(response.status);
		if (response.successResponse != null) {
			strBuffer.append(", ").append("success-response ").append(String.valueOf(response.successResponse));
		}
		if (response.errorResponse != null) {
			strBuffer.append(", ").append("error-response ").append(String.valueOf(response.errorResponse));
		}
		if (response.exception != null) {
			strBuffer.append(", ").append(String.valueOf(response.exception));
		}
		return strBuffer.toString();
	}
}
