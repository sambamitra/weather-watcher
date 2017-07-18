package weatherwatcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Properties;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;

import com.amazon.speech.slu.Intent;
import com.amazon.speech.slu.Slot;
import com.amazon.speech.speechlet.IntentRequest;
import com.amazon.speech.speechlet.LaunchRequest;
import com.amazon.speech.speechlet.Session;
import com.amazon.speech.speechlet.SessionEndedRequest;
import com.amazon.speech.speechlet.SessionStartedRequest;
import com.amazon.speech.speechlet.Speechlet;
import com.amazon.speech.speechlet.SpeechletException;
import com.amazon.speech.speechlet.SpeechletResponse;
import com.amazon.speech.ui.OutputSpeech;
import com.amazon.speech.ui.PlainTextOutputSpeech;
import com.amazon.speech.ui.Reprompt;
import com.amazon.speech.ui.SimpleCard;
import com.amazon.speech.ui.SsmlOutputSpeech;
import com.amazonaws.util.json.JSONArray;
import com.amazonaws.util.json.JSONException;
import com.amazonaws.util.json.JSONObject;
import com.amazonaws.util.json.JSONTokener;

/**
 * This is a Lambda function for handling Alexa Skill requests that:
 * <ul>
 * <li><b>Web service</b>: communicate with an external web service to get
 * weather data from OpenWeatherMap API (http://openweathermap.org/api)</li>
 * <li><b>SSML</b>: Using SSML tags to control how Alexa renders the
 * text-to-speech</li>
 * <p>
 * - Dialog and Session state: Handles two models, both a one-shot ask and tell
 * model, and a multi-turn dialog model. If the user provides an incorrect slot
 * in a one-shot model, it will direct to the dialog model. See the examples
 * section for sample interactions of these models.
 * </ul>
 * <p>
 * <h2>Examples</h2>
 * <p>
 * <b>One-shot model</b>
 * <p>
 * User: "Alexa, ask Weather Watcher what is the current weather in Newcastle
 * upon Tyne" Alexa: "The current temperature in Newcastle upon Tyne is ..."
 * <p>
 * <b>Dialog model</b>
 * <p>
 * User: "Alexa, open Weather Watcher"
 * <p>
 * Alexa: "Welcome to Weather Watcher. Which city would you like weather
 * information for?"
 * <p>
 * User: "Newcastle upon Tyne"
 * <p>
 * Alexa: "The current temperature in Newcastle upon Tyne is ..."
 */
public class WeatherWatcherSpeechlet implements Speechlet {
	private static final Logger log = Logger.getLogger(WeatherWatcherSpeechlet.class);

	// Configuration properties - to be initialized in a static block below
	private static final Properties CONFIG_PROPERTIES;
	private static final Properties WEATHER_CONDITIONS_PROPERTIES;

	// Intents
	private static final String ONE_SHOT_WEATHER_INTENT = "OneShotWeatherIntent";
	private static final String DIALOG_WEATHER_INTENT = "DialogWeatherIntent";
	private static final String FORECAST_INTENT = "ForecastIntent";
	private static final String AMAZON_HELP_INTENT = "AMAZON.HelpIntent";
	private static final String AMAZON_STOP_INTENT = "AMAZON.StopIntent";
	private static final String AMAZON_CANCEL_INTENT = "AMAZON.CancelIntent";

	// Slots
	private static final String CITY_SLOT = "City";

	// Session
	private static final String SESSION_CITY = "City";

	// Configuration file
	private static final String CONFIG_FILE_NAME = "configuration.properties";
	private static final String WEATHER_CONDITIONS_FILE_NAME = "weather_conditions.properties";

	// Configuration file entries below help construct the REST endpoint
	private static final String SERVICE_ENDPOINT = "serviceEndpoint";
	private static final String CONTEXT_PATH_WEATHER = "contextPathWeather";
	private static final String CONTEXT_PATH_FORECAST = "contextPathForecast";
	private static final String CITY_NAME_QUERY_PARAM = "cityNameQueryParam";
	private static final String APP_ID = "APPID";

	// Other constants
	private static final String REQ_METHOD = "GET";

	static {
		CONFIG_PROPERTIES = new Properties();
		WEATHER_CONDITIONS_PROPERTIES = new Properties();
		try (InputStream is = WeatherWatcherSpeechlet.class.getClassLoader().getResourceAsStream(CONFIG_FILE_NAME);) {
			CONFIG_PROPERTIES.load(is);
		} catch (IOException e) {
			log.error("Cannot load properties: " + e.getLocalizedMessage());
		}
		try (InputStream is = WeatherWatcherSpeechlet.class.getClassLoader()
				.getResourceAsStream(WEATHER_CONDITIONS_FILE_NAME);) {
			WEATHER_CONDITIONS_PROPERTIES.load(is);
		} catch (IOException e) {
			log.error("Cannot load properties: " + e.getLocalizedMessage());
		}
	}

	@Override
	public void onSessionStarted(final SessionStartedRequest request, final Session session) throws SpeechletException {
		log.info("onSessionStarted requestId=" + request.getRequestId() + ", sessionId=" + session.getSessionId());
		log.info("User=" + session.getUser().getUserId() + ", Access Token=" + session.getUser().getAccessToken());

		// any initialization logic goes here
	}

	@Override
	public void onSessionEnded(final SessionEndedRequest request, final Session session) throws SpeechletException {
		log.info("onSessionEnded requestId=" + request.getRequestId() + ", sessionId=" + session.getSessionId());
	}

	@Override
	public SpeechletResponse onLaunch(final LaunchRequest request, final Session session) throws SpeechletException {
		log.info("onLaunch requestId=" + request.getRequestId() + ", sessionId=" + session.getSessionId());
		return getWelcomeResponse();
	}

	@Override
	public SpeechletResponse onIntent(final IntentRequest request, final Session session) throws SpeechletException {
		log.info("onIntent requestId=" + request.getRequestId() + ", sessionId=" + session.getSessionId());

		Intent intent = request.getIntent();
		if (intent == null) {
			throw new SpeechletException("Invalid Intent");
		}
		String intentName = intent.getName();

		switch (intentName) {
		case ONE_SHOT_WEATHER_INTENT:
			return handleOneShotWeatherRequest(intent, session);
		case DIALOG_WEATHER_INTENT:
			Slot citySlot = intent.getSlot(CITY_SLOT);
			if (citySlot != null && citySlot.getValue() != null) {
				return handleCityDialogRequest(intent, session);
			}
			return handleNoSlotDialogRequest(intent, session);
		case AMAZON_HELP_INTENT:
			return handleHelpRequest();
		case AMAZON_STOP_INTENT:
		case AMAZON_CANCEL_INTENT:
			PlainTextOutputSpeech outputSpeech = new PlainTextOutputSpeech();
			outputSpeech.setText("Goodbye and enjoy the weather!");
			return SpeechletResponse.newTellResponse(outputSpeech);
		default:
			throw new SpeechletException("Invalid Intent");
		}
	}

	/**
	 * This handles the one-shot weather interaction, where the user utters a
	 * phrase like: 'Alexa, open Weather Watcher and get weather information for
	 * Newcastle upon Tyne'. If there is an error in a slot, this will guide the
	 * user to the dialog approach.
	 */
	private static SpeechletResponse handleOneShotWeatherRequest(final Intent intent, final Session session) {
		// Get the city from intent
		final String city = intent.getSlot(CITY_SLOT).getValue();
		return callWeatherApi(city);
	}

	/**
	 * Handle no slots, or slot(s) with no values. In the case of a dialog based
	 * skill with multiple slots, when passed a slot with no value, we cannot
	 * have confidence it is is the correct slot type so we rely on session
	 * state to determine the next turn in the dialog, and reprompt.
	 */
	private static SpeechletResponse handleNoSlotDialogRequest(final Intent intent, final Session session) {
		if (session.getAttributes().containsKey(SESSION_CITY)) {
			final String city = (String) session.getAttribute(SESSION_CITY);
			return callWeatherApi(city);
		}
		// get city re-prompt
		return handleCityRequest(intent, session);
	}

	/**
	 * Handles the dialog step where the user provides a city.
	 */
	private static SpeechletResponse handleCityDialogRequest(final Intent intent, final Session session) {
		final String city = intent.getSlot(CITY_SLOT).getValue();
		return callWeatherApi(city);
	}

	// Handlers

	/**
	 * Defines a greeting text to be read when user activates the skill without
	 * a specific intent. Calls getSpeechletResponse for generating the
	 * SpeechletResponse.
	 * 
	 * @return SpeechletResponse
	 */
	private static SpeechletResponse getWelcomeResponse() {
		String whichCityPrompt = "Which city would you like current weather for?";
		String speechOutput = "<speak>Welcome to Weather Watcher. I can provide the current weather for any city. "
				+ whichCityPrompt + "</speak>";
		String repromptText = "I can lead you through providing a city to get weather information, "
				+ "or you can simply open Weather Watcher and ask a question like, "
				+ "get current weather for Newcastle upon Tyne. " + whichCityPrompt;

		return newAskResponse(speechOutput, true, repromptText, false);
	}

	private static SpeechletResponse handleHelpRequest() {
		String repromptText = "Which city would you like current weather for?";
		String speechOutput = "I can lead you through providing a city to get weather information, "
				+ "or you can simply open Weather Watcher and ask a question like, "
				+ "get current weather for Newcastle upon Tyne. " + "Or you can say Cancel. " + repromptText;

		return newAskResponse(speechOutput, false, repromptText, false);
	}

	/**
	 * Wrapper for creating the Ask response from the input strings.
	 *
	 * @param stringOutput
	 *            the output to be spoken
	 * @param isOutputSsml
	 *            whether the output text is of type SSML
	 * @param repromptText
	 *            the reprompt for if the user doesn't reply or is
	 *            misunderstood.
	 * @param isRepromptSsml
	 *            whether the reprompt text is of type SSML
	 * @return SpeechletResponse the speechlet response
	 */
	private static SpeechletResponse newAskResponse(final String speechOutput, final boolean isOutputSsml,
			final String repromptText, final boolean isRepromptSsml) {

		OutputSpeech outputSpeech, repromptOutputSpeech;
		if (isOutputSsml) {
			outputSpeech = new SsmlOutputSpeech();
			((SsmlOutputSpeech) outputSpeech).setSsml(speechOutput);
		} else {
			outputSpeech = new PlainTextOutputSpeech();
			((PlainTextOutputSpeech) outputSpeech).setText(speechOutput);
		}

		if (isRepromptSsml) {
			repromptOutputSpeech = new SsmlOutputSpeech();
			((SsmlOutputSpeech) repromptOutputSpeech).setSsml(speechOutput);
		} else {
			repromptOutputSpeech = new PlainTextOutputSpeech();
			((PlainTextOutputSpeech) repromptOutputSpeech).setText(repromptText);
		}

		Reprompt reprompt = new Reprompt();
		reprompt.setOutputSpeech(repromptOutputSpeech);
		return SpeechletResponse.newAskResponse(outputSpeech, reprompt);
	}

	private static SpeechletResponse handleCityRequest(final Intent intent, final Session session) {
		// get city re-prompt
		String repromptText = "Which city would you like current weather for?";
		String speechOutput = "I can provide the current weather for any city. " + repromptText;

		return newAskResponse(speechOutput, false, repromptText, false);
	}

	/**
	 * Uses OpenWeatherMap API.
	 *
	 * @see <a href = "http://openweathermap.org/api">OpenWeatherMap API</a>
	 * @param city
	 *            - City for which weather information is needed
	 * @param forecast
	 *            - boolean indicating whether the weather requested is for
	 *            current data or forecast
	 * @throws IOException
	 */
	private static SpeechletResponse callWeatherApi(final String city) {
		final String endpoint = CONFIG_PROPERTIES.getProperty(SERVICE_ENDPOINT);
		final String weatherContext = CONFIG_PROPERTIES.getProperty(CONTEXT_PATH_WEATHER);
		final String forecastContext = CONFIG_PROPERTIES.getProperty(CONTEXT_PATH_FORECAST);
		final String cityParam = CONFIG_PROPERTIES.getProperty(CITY_NAME_QUERY_PARAM);
		final String appId = CONFIG_PROPERTIES.getProperty(APP_ID);
		String queryString = "?" + cityParam + "=" + city + "&APPID=" + appId + "&units=metric";

		// Get the speech outputs
		final String currentWeatherSppechOutput = getWeatherDetails(endpoint, weatherContext, queryString, city);
		final String weatherForecastSppechOutput = getWeatherDetails(endpoint, forecastContext, queryString, city);
		final String speechOutput = currentWeatherSppechOutput + weatherForecastSppechOutput;

		// Create the Simple card content.
		SimpleCard card = new SimpleCard();
		card.setTitle("Weather Watcher");
		card.setContent(speechOutput);

		// Create the plain text output
		PlainTextOutputSpeech outputSpeech = new PlainTextOutputSpeech();
		outputSpeech.setText(speechOutput);

		return SpeechletResponse.newTellResponse(outputSpeech, card);
	}

	private static String getWeatherDetails(final String endpoint, final String context, final String queryString,
			final String city) {

		String speechOutput = "";
		String line = null;
		URL url = null;
		try {
			url = new URL(endpoint + context + queryString);
		} catch (MalformedURLException e) {
			log.error("Exception occoured while forming the url.", e);
		}
		if (url == null) {
			return "Sorry, there is a problem with Weather Watcher. Please try again later.";
		}

		StringBuilder builder = new StringBuilder();

		try (InputStreamReader inputStreamReader = new InputStreamReader(url.openStream());
				BufferedReader bufferedReader = new BufferedReader(inputStreamReader);) {
			log.info("OpenWeatherMap URL formed : " + url);
			while ((line = bufferedReader.readLine()) != null) {
				builder.append(line);
			}
		} catch (IOException e) {
			// reset builder to a blank string
			log.error("Exception occoured while reading the stream from the url.", e);
			builder.setLength(0);
		}

		if (builder.length() == 0) {
			speechOutput = "Sorry, the Open Weather Map service is experiencing a problem. "
					+ "Please try again later.";
			return speechOutput;
		}

		return generateSpeechOutput(city, builder);
	}

	/**
	 * @param city
	 * @param speechOutput
	 * @param builder
	 * @return
	 */
	private static String generateSpeechOutput(final String city, StringBuilder builder) {
		String speechOutput = "";
		try {
			JSONObject openWeatherMapResponseObject = new JSONObject(new JSONTokener(builder.toString()));
			final JSONObject tempPressure = openWeatherMapResponseObject.getJSONObject("main");
			final long temp = Math.round(tempPressure.getDouble("temp"));
			final long temp_min = Math.round(tempPressure.getDouble("temp_min"));
			final long temp_max = Math.round(tempPressure.getDouble("temp_max"));

			final JSONArray weather = openWeatherMapResponseObject.getJSONArray("weather");
			JSONObject weatherObj;
			String weatherDesc = "";
			if (weather != null) {
				for (int i = 0; i < weather.length(); i++) {
					weatherObj = weather.getJSONObject(i);
					weatherDesc = weatherObj != null ? weatherObj.getString("description") : "";
					weatherDesc = weatherDesc + "and ";
				}
			}
			if (weatherDesc.contains("and")) {
				weatherDesc = weatherDesc.substring(0, weatherDesc.lastIndexOf("and"));
			}
			log.info("Weather description : " + weatherDesc);

			StringBuilder speechBuilder = new StringBuilder();
			speechBuilder.append("It is ").append(temp).append(" degree celsius ");
			if (StringUtils.isNotBlank(weatherDesc)) {
				speechBuilder.append("with ").append(weatherDesc);
			}
			speechBuilder.append(" in ").append(city).append(". Today's maximum temperature is ");
			speechBuilder.append(temp_max).append(" degree celsius and minimum temperature is ");
			speechBuilder.append(temp_min).append(" degree celsius.");

			speechOutput = speechBuilder.toString();
		} catch (JSONException e) {
			log.error("Exception occoured while parsing service response.", e);
		}

		return speechOutput;
	}

}
