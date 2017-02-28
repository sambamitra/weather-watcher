package weatherwatcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.util.Properties;

import org.apache.commons.io.IOUtils;
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
import com.amazon.speech.ui.SsmlOutputSpeech;
import com.amazon.speech.ui.Reprompt;
import com.amazon.speech.ui.SimpleCard;
import com.amazonaws.util.json.JSONException;
import com.amazonaws.util.json.JSONObject;
import com.amazonaws.util.json.JSONTokener;

/**
 * This is a Lambda function for handling Alexa Skill requests that:
 * <ul>
 * <li><b>Web service</b>: communicate with an external web service to get
 * weather data from OpenWeatherMap API (http://openweathermap.org/api)</li>
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

	// Configuration file entries below help construct the REST endpoint
	// for the medical record management service
	private static final String SERVICE_ENDPOINT = "serviceEndpoint";
	private static final String CONTEXT_PATH_WEATHER = "contextPathWeather";
	private static final String CONTEXT_PATH_FORECAST = "contextPathForecast";
	private static final String CITY_NAME_QUERY_PARAM = "cityNameQueryParam";
	private static final String APP_ID = "APPID";

	// Other constants
	private static final String REQ_METHOD = "GET";

	static {
		InputStream is = null;
		CONFIG_PROPERTIES = new Properties();
		try {
			is = WeatherWatcherSpeechlet.class.getClassLoader().getResourceAsStream(CONFIG_FILE_NAME);
			CONFIG_PROPERTIES.load(is);
		} catch (IOException e) {
			log.error("Cannot load properties: " + e.getLocalizedMessage());
		} finally {
			try {
				is.close();
			} catch (IOException e) {
				log.error("Cannot close stream: " + e.getLocalizedMessage());
			}
		}
	}

	@Override
	public void onSessionStarted(final SessionStartedRequest request, final Session session) throws SpeechletException {
		log.info("onSessionStarted requestId=" + request.getRequestId() + ", sessionId=" + session.getSessionId());
		log.info("User=" + session.getUser().getUserId() + ", Access Token=" + session.getUser().getAccessToken());

		// any initialization logic goes here
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
		String intentName = (intent != null) ? intent.getName() : null;

		switch (intentName) {
		case ONE_SHOT_WEATHER_INTENT:
			return handleWeatherRequest(intent, session);
		case DIALOG_WEATHER_INTENT:
			Slot citySlot = intent.getSlot(CITY_SLOT);
			if (citySlot != null && citySlot.getValue() != null) {
				return handleCityDialogRequest(intent, session);
			} else {
				return handleNoSlotDialogRequest(intent, session);
			}
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
	 * Handles the dialog step where the user provides a city.
	 */
	private SpeechletResponse handleCityDialogRequest(final Intent intent, final Session session) {
		final String city = intent.getSlot(CITY_SLOT).getValue();

		return callWeatherApi(city, false);

	}

	@Override
	public void onSessionEnded(final SessionEndedRequest request, final Session session) throws SpeechletException {
		log.info("onSessionEnded requestId=" + request.getRequestId() + ", sessionId=" + session.getSessionId());
	}

	// Handlers

	/**
	 * Defines a greeting text to be read when user activates the skill without
	 * a specific intent. Calls getSpeechletResponse for generating the
	 * SpeechletResponse.
	 * 
	 * @return SpeechletResponse
	 */
	private SpeechletResponse getWelcomeResponse() {
		String whichCityPrompt = "Which city would you like current weather for?";
		String speechOutput = "<speak>Welcome to Weather Watcher. I can provide the current weather for any city. "
				+ whichCityPrompt + "</speak>";
		String repromptText = "I can lead you through providing a city to get weather information, "
				+ "or you can simply open Weather Watcher and ask a question like, "
				+ "get current weather for Newcastle upon Tyne. " + whichCityPrompt;

		return newAskResponse(speechOutput, true, repromptText, false);
	}

	private SpeechletResponse handleHelpRequest() {
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
	private SpeechletResponse newAskResponse(final String speechOutput, final boolean isOutputSsml,
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

	/**
	 * This handles the one-shot weather interaction, where the user utters a
	 * phrase like: 'Alexa, open Weather Watcher and get weather information for
	 * Newcastle upon Tyne'. If there is an error in a slot, this will guide the
	 * user to the dialog approach.
	 */
	private SpeechletResponse handleWeatherRequest(final Intent intent, final Session session) {
		// Get the city from intent
		final String city = intent.getSlot(CITY_SLOT).getValue();
		return callWeatherApi(city, false);
	}

	/**
	 * Handle no slots, or slot(s) with no values. In the case of a dialog based
	 * skill with multiple slots, when passed a slot with no value, we cannot
	 * have confidence it is is the correct slot type so we rely on session
	 * state to determine the next turn in the dialog, and reprompt.
	 */
	private SpeechletResponse handleNoSlotDialogRequest(final Intent intent, final Session session) {
		if (session.getAttributes().containsKey(SESSION_CITY)) {
			final String city = (String) session.getAttribute(SESSION_CITY);
			return callWeatherApi(city, false);
		} else {
			// get city re-prompt
			return handleCityRequest(intent, session);
		}
	}

	private SpeechletResponse handleCityRequest(final Intent intent, final Session session) {
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
	private SpeechletResponse callWeatherApi(final String city, final boolean forecast) {
		String speechOutput = "";

		final String endpoint = CONFIG_PROPERTIES.getProperty(SERVICE_ENDPOINT);
		final String weatherContext = CONFIG_PROPERTIES.getProperty(CONTEXT_PATH_WEATHER);
		final String forecastContext = CONFIG_PROPERTIES.getProperty(CONTEXT_PATH_FORECAST);
		final String cityParam = CONFIG_PROPERTIES.getProperty(CITY_NAME_QUERY_PARAM);
		final String appId = CONFIG_PROPERTIES.getProperty(APP_ID);

		String queryString = "?" + cityParam + "=" + city + "&APPID=" + appId + "&units=metric";

		InputStreamReader inputStream = null;
		BufferedReader bufferedReader = null;

		StringBuilder builder = new StringBuilder();

		try {
			String line;
			URL url;
			if (forecast) {
				url = new URL(endpoint + forecastContext + queryString);
			}
			url = new URL(endpoint + weatherContext + queryString);
			log.info("OpenWeatherMap URL formed : " + url);
			inputStream = new InputStreamReader(url.openStream(), Charset.forName("US-ASCII"));
			bufferedReader = new BufferedReader(inputStream);
			while ((line = bufferedReader.readLine()) != null) {
				builder.append(line);
			}
		} catch (IOException e) {
			// reset builder to a blank string
			builder.setLength(0);
		} finally {
			IOUtils.closeQuietly(inputStream);
			IOUtils.closeQuietly(bufferedReader);
		}

		if (builder.length() == 0) {
			speechOutput = "Sorry, the Open Weather Map service is experiencing a problem. "
					+ "Please try again later.";
		} else {
			try {
				JSONObject openWeatherMapResponseObject = new JSONObject(new JSONTokener(builder.toString()));
				if (openWeatherMapResponseObject != null) {
					final JSONObject mainWeather = openWeatherMapResponseObject.getJSONObject("main");
					final double temp = mainWeather.getDouble("temp");
					final double temp_min = mainWeather.getDouble("temp_min");
					final double temp_max = mainWeather.getDouble("temp_max");

					speechOutput = new StringBuilder().append("The current temperature in ").append(city).append(" is ")
							.append(temp).append(" degree celsius. Today's maximum temperature is ").append(temp_max)
							.append(" degree celsius, and minimum temperature is ").append(temp_min)
							.append(" degree celsius.").toString();
				}
			} catch (JSONException e) {
				log.error("Exception occoured while parsing service response.", e);
			}
		}

		// Create the Simple card content.
		SimpleCard card = new SimpleCard();
		card.setTitle("Weather Watcher");
		card.setContent(speechOutput);

		// Create the plain text output
		PlainTextOutputSpeech outputSpeech = new PlainTextOutputSpeech();
		outputSpeech.setText(speechOutput);

		return SpeechletResponse.newTellResponse(outputSpeech, card);
	}

}
