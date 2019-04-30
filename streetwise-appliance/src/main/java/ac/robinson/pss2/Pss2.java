package ac.robinson.pss2;

import com.pi4j.io.gpio.*;
import com.pi4j.io.gpio.event.GpioPinListenerDigital;
import com.pi4j.wiringpi.Gpio;
import com.pi4j.wiringpi.SoftPwm;
import io.reactivex.disposables.Disposable;
import okhttp3.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Pss2 {

	// The URL (including path where necessary) of the StreetWise server. This should include the trailing slash.
	private static final String SERVER_URL = "*** YOUR SERVER URL HERE ***";

	// The API key ($SPEECH_APPLIANCE_KEY) that you chose in your StreetWise server configuration.
	static final String SERVER_KEY = "*** YOUR SPEECH APPLIANCE API KEY HERE ***";

	// Pss2.logEvent(System.getProperty("user.dir")); // relative directory
	private static final File HOME_DIRECTORY = new File("/home/pi/pss2");

	@SuppressWarnings("ArraysAsListWithZeroOrOneArgument")
	private static final List<String> VALID_LANGUAGES = Arrays.asList("hi");
	private static final String LANGUAGE = "hi"; // audio language - overridden from /boot/pss2language on start
	private static final int OUTPUT_VOLUME = 100; // default (percentage)  - overridden from /boot/pss2volume on start
	private static final int FALLBACK_SYSTEM_ID = 20; // if /boot/pss2id reading fails, use this ID

	// lower to require a more silent background (e.g., -70); higher to tolerate more noise (e.g., -50)
	private static final double SILENCE_THRESHOLD_DB = -45; // decibel level for silence detection - /boot/pss2silence
	static final int SILENCE_TIMEOUT_MS = 3 * 1000; // ms of silence before stopping recording after minimum duration
	static final int DURATION_MINIMUM_MS = (1 + 4) * 1000; // ms minimum duration of recording ("ask now" + time)
	static final int DURATION_MAXIMUM_MS = 15 * 1000; // ms maximum duration of recording

	private static final int LED_FADE_INTENSITY = 70; // when no interaction, fade led from 0 to this value (max: 100)
	private static final int LED_BLINK_INTERVAL_ACTIVE = 200; // ms blink interval when something is happening


	private static PrintWriter mLogWriter; // static so we can share between classes

	private static OkHttpClient sOkHttpClient;

	private GpioController mGpio;
	private GpioPinDigitalOutput mLed;
	private GpioPinDigitalInput mButton;

	private enum LedState {Off, Fading, Blinking, On}

	private LedFader mLedFader;
	private IndeterminateProgressUpdater mProgressUpdater;
	private Thread mProgressUpdaterThread;

	private SoundPlayer mSoundPlayer;
	private SilenceDetectingAudioRecorder mAudioRecorder;
	private NumberPlaybackCallback mNumberPlaybackCallback;

	private AnswerDownloader mAnswerDownloader;

	private PssIO pssIO;

	private Disposable answerQueryNumSub;

	private int mPss2Id;
	private int mPss2Volume;
	private double mPss2SilenceThreshold;
	private String mPss2Language;

	private boolean mRecordingEnabled;

	// pattern is: [id]_[type]_[recording timestamp].mp3
	static final Pattern QUESTION_FILE_PATTERN = Pattern.compile(
			"(\\d+)_question_(\\d+)_(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2})\\" +
					SilenceDetectingAudioRecorder.MP3_AUDIO_FILE_EXTENSION);
	private static final String QUESTION_FILE_FORMAT = "%d_question_%s"; // for renaming with ids
	static final Pattern ANSWER_FILE_PATTERN = Pattern.compile(
			"(\\d+)_answer_(\\d+)_" + "(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2})\\.(mp3|m4a|wav)");
	static final String ANSWER_FILE_FORMAT = "%d_answer_%s"; // time = downloaded

	// for file uploads and response retrieval
	private static final String FILE_SERVER_URL = String.format("%s%s", SERVER_URL, "question.php");
	static final String ANSWER_SERVER_URL = String.format("%s%s", SERVER_URL, "responses.php");

	private void initialise() {
		if (!HOME_DIRECTORY.mkdirs() && !HOME_DIRECTORY.exists()) {
			Pss2.logEvent("Unable to create home directories - exiting");
			System.exit(-1);
		}

		Pss2.logEvent("Initialising application");

		try {
			List<String> lines = Files.readAllLines(Paths.get("/boot", "pss2id"));
			mPss2Id = Integer.valueOf(lines.get(0));
			Pss2.logEvent("Pss2 ID loaded: " + mPss2Id);
		} catch (IOException | IndexOutOfBoundsException | NumberFormatException e) {
			Pss2.logEvent("Error reading Pss2 ID - setting to " + FALLBACK_SYSTEM_ID + " as fallback");
			mPss2Id = FALLBACK_SYSTEM_ID;
		}

		try {
			List<String> lines = Files.readAllLines(Paths.get("/boot", "pss2volume"));
			mPss2Volume = Integer.valueOf(lines.get(0));
			Pss2.logEvent("Pss2 volume loaded: " + mPss2Volume);
		} catch (IOException | IndexOutOfBoundsException | NumberFormatException e) {
			Pss2.logEvent("Error reading Pss2 volume - setting to " + OUTPUT_VOLUME + "% as fallback");
			mPss2Volume = OUTPUT_VOLUME;
		}

		try {
			List<String> lines = Files.readAllLines(Paths.get("/boot", "pss2silence"));
			mPss2SilenceThreshold = Double.valueOf(lines.get(0));
			Pss2.logEvent("Pss2 silence threshold loaded: " + mPss2SilenceThreshold);
		} catch (IOException | IndexOutOfBoundsException | NumberFormatException e) {
			Pss2.logEvent(
					"Error reading Pss2 silence threshold - setting to " + SILENCE_THRESHOLD_DB + "% as fallback");
			mPss2SilenceThreshold = SILENCE_THRESHOLD_DB;
		}

		try {
			List<String> lines = Files.readAllLines(Paths.get("/boot", "pss2language"));
			mPss2Language = lines.get(0);
			if (!VALID_LANGUAGES.contains(mPss2Language)) {
				throw new IllegalArgumentException("Invalid boot language code");
			}
			Pss2.logEvent("Pss2 language loaded: " + mPss2Language);
		} catch (IOException | IndexOutOfBoundsException | IllegalArgumentException e) {
			Pss2.logEvent("Error reading Pss2 language - setting to " + LANGUAGE + "% as fallback");
			mPss2Language = LANGUAGE;
		}

		setupGpio();
		setupSounds();

		pssIO = new PssIO();
		pssIO.initialize();
		pssIO.blockUserInput();

		mProgressUpdater = new IndeterminateProgressUpdater();
		mProgressUpdaterThread = new Thread(mProgressUpdater);
		mProgressUpdaterThread.start(); // on start, just show the loading symbol

		mAudioRecorder = new SilenceDetectingAudioRecorder(HOME_DIRECTORY, pssIO);
		mNumberPlaybackCallback = new NumberPlaybackCallback();
		answerQueryNumSub = pssIO.getAnswerQueryNumbers().subscribe(this::getAndPlayAnswer);

		mLed.setState(true);

		new Thread(() -> {
			Pss2.logEvent("Checking internet connection status...");
			int internetCheckResult = 0; // 0 = no internet; 1 = connected
			String[] internetScript = new String[]{
					"/bin/bash",
					"-c",
					"wget -q --tries=10 --timeout=20 --spider http://clients3.google.com/generate_204; if [ $? -eq 0" +
							" ]; then echo 1; else echo 0; fi"
			};
			while (internetCheckResult == 0) {
				try {
					Thread.sleep(1000);
					Process internetCheckProcess = Runtime.getRuntime().exec(internetScript);
					BufferedReader stdInput =
							new BufferedReader(new InputStreamReader(internetCheckProcess.getInputStream()));
					internetCheckResult = Integer.parseInt(stdInput.readLine());
				} catch (IOException | InterruptedException | NumberFormatException ignored) {
				}
			}

			Pss2.logEvent("Working internet connection found - completing startup");
			mRecordingEnabled = true;
			pssIO.enableUserInput(false);
			mProgressUpdater.stop();
			mButton.addListener(mButtonListener);
			mSoundPlayer.playSoundResource(SoundPlayer.Sound.start);
			mAnswerDownloader = new AnswerDownloader(HOME_DIRECTORY, mPss2Id);
		}).start();
	}

	private void destroy() {
		Pss2.logEvent("Shutting down application");

		setLedState(LedState.Off);
		mProgressUpdater.stop();
		mGpio.shutdown(); // terminate Pi4J GPIO
		if (mAnswerDownloader != null) {
			mAnswerDownloader.stop();
		}
		answerQueryNumSub.dispose();

		// TODO: terminate all sounds and free their resources

		if (mLogWriter != null) {
			mLogWriter.close();
		}
	}

	private void setupGpio() {
		Gpio.wiringPiSetup(); // needed for PWM
		mGpio = GpioFactory.getInstance();

		mLedFader = new LedFader();
		mLed = mGpio.provisionDigitalOutputPin(RaspiPin.GPIO_06); // note: uses Wiring Pi numbering

		mButton = mGpio.provisionDigitalInputPin(RaspiPin.GPIO_04, PinPullResistance.PULL_DOWN);
	}

	private void setLedState(LedState state) {
		mLedFader.stop();
		SoftPwm.softPwmStop(LedFader.LED_PIN); // stop any PWM on this pin (e.g., other processes, previous commands)
		mLed.blink(0); // stop any blinking that was previously happening
		mLed.setState(false); // turn off initially

		switch (state) {
			case On: // on = waiting for user input
				mRecordingEnabled = true;
				mProgressUpdater.stop();
				pssIO.enableUserInput(true);
				mLed.setState(true);
				break;
			case Fading: // (fading = no action currently)
				break;
			case Blinking: // blinking = recording audio / user input blocked
				pssIO.blockUserInput();
				mLed.blink(LED_BLINK_INTERVAL_ACTIVE);
				break;
			case Off: // off = non-button action happening; input disabled
				pssIO.blockUserInput();
				mLed.setState(false);
				break;
		}
	}

	private class IndeterminateProgressUpdater implements Runnable {
		private boolean mRunning;
		private int mNumber;

		@Override
		public void run() {
			int mProgress = 0;
			mNumber = -1;
			mRunning = true;
			while (mRunning) {
				pssIO.displayIndeterminateProgress(-mProgress, 120);
				mProgress = (mProgress + 2) % 360;
			}
			pssIO.clearDisplay();
			if (mNumber >= 0) {
				pssIO.displayNumber(mNumber);
			}
		}

		private void stop() {
			try {
				mRunning = false;
				if (mProgressUpdaterThread != null) {
					mProgressUpdaterThread.join();
				}
			} catch (InterruptedException ignored) {
			}
		}

		private void stop(int number) {
			mNumber = number;
			stop();
		}
	}

	private class LedFader implements Runnable {
		private static final int LED_PIN = 6;
		private static final int FADE_DELAY = 25; // ms
		private boolean mRunning;

		@Override
		public void run() {
			mRunning = true;
			try {
				while (mRunning) {
					for (int i = 0; i <= LED_FADE_INTENSITY; i++) {
						SoftPwm.softPwmWrite(LED_PIN, i);
						Thread.sleep(FADE_DELAY);
					}
					Thread.sleep((FADE_DELAY * (100 - LED_FADE_INTENSITY)) / 2);
					for (int i = LED_FADE_INTENSITY; i >= 0; i--) {
						SoftPwm.softPwmWrite(LED_PIN, i);
						Thread.sleep(FADE_DELAY);
					}
					Thread.sleep((FADE_DELAY * (100 - LED_FADE_INTENSITY)) / 2);
				}
			} catch (InterruptedException ignored) {
			}
		}

		private void stop() {
			mRunning = false;
			SoftPwm.softPwmStop(LedFader.LED_PIN);
		}
	}

	private void setupSounds() {
		try {
			Runtime.getRuntime().exec("amixer sset Master,0 " + mPss2Volume + "%"); // set system volume
		} catch (IOException e) {
			Pss2.logEvent("Warning: unable to set sound to maximum volume");
		}
		mSoundPlayer = new SoundPlayer(mPss2Language);
	}

	public static void main(String[] args) {
		Pss2 pss2 = new Pss2();
		pss2.initialise();

		//noinspection InfiniteLoopStatement
		while (true) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				pss2.destroy();
			}
		}
	}

	private final GpioPinListenerDigital mButtonListener = gpioPinDigitalStateChangeEvent -> {
		if (gpioPinDigitalStateChangeEvent.getState().isHigh()) {
			Pss2.logEvent("Button up");
		} else {
			Pss2.logEvent("Button down");
			if (!mRecordingEnabled ||
					!mAudioRecorder.startRecording(mPss2SilenceThreshold,
							new SilenceDetectingAudioRecorder.RecordingCompletedCallback() {
						@Override
						public void onRecordingStarted() {
							Pss2.logEvent("Recording started (callback)");
							// TODO: start recording after instead? ...problem = failures happen after "ask now"
							mSoundPlayer.playSoundResource(SoundPlayer.Sound.begin_question);
							mRecordingEnabled = false;
							setLedState(LedState.Blinking);
						}

						@Override
						public void onEncodingStarted() {
							Pss2.logEvent("Encoding completed (callback)");
							mProgressUpdater.stop();
							mProgressUpdaterThread = new Thread(mProgressUpdater);
							mProgressUpdaterThread.start();
							setLedState(LedState.Off);
						}

						@Override
						public void onRecordingCompleted(File outputFile) {
							Pss2.logEvent("Recording completed (callback) to: " + outputFile.getAbsolutePath());
							uploadQuestion(outputFile);
						}

						@Override
						public void onRecordingFailed() {
							Pss2.logEvent("Recording failed (callback)");
							mSoundPlayer.playSoundResource(SoundPlayer.Sound.error);
							setLedState(LedState.On);
						}
					})) {
				Pss2.logEvent(
						"Unable to start recording (already recording? Recording enabled: " + mRecordingEnabled + ")");
			}
		}
	};

	private File getLocalAudioFile(Pattern pattern, int localId) {
		// find a file named according to our system
		File[] fileList = HOME_DIRECTORY.listFiles();
		if (fileList != null) {
			for (File file : fileList) {
				String fileName = file.getName();
				if (file.isFile()) {
					Matcher matcher = pattern.matcher(fileName);
					if (matcher.matches()) {
						try {
							int fileNumber = Integer.parseInt(matcher.group(1));
							if (fileNumber == localId) {
								return file;
							}
						} catch (Exception ignored) {
						}
					}
				}
			}
		}
		return null;
	}

	private void uploadQuestion(File questionFile) {
		Pss2.logEvent("Uploading " + questionFile.getName() + " to server");
		try {
			RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
					.addFormDataPart("question", questionFile.getName(), RequestBody.create(MediaType.parse(
							"audio" + "/mpeg"), questionFile))
					.addFormDataPart("source", String.valueOf(mPss2Id))
					.addFormDataPart("k", SERVER_KEY)
					.build();
			Request request = new Request.Builder().url(FILE_SERVER_URL).post(requestBody).build();

			getOkHttpClient().newCall(request).enqueue(new Callback() {
				@Override
				public void onFailure(Call call, IOException e) {
					onError("Upload request failed: " + call + ", " + e.getLocalizedMessage());
				}

				@Override
				public void onResponse(Call call, Response response) throws IOException {
					ResponseBody responseBody = response.body();
					if (!response.isSuccessful() || responseBody == null) {
						onError("Upload response unsuccessful: " +
								(responseBody != null ? responseBody.string() : null));
					} else {
						String responseString = responseBody.string();
						if ("error".equals(responseString)) {
							onError("Upload response unsuccessful: " + responseString);
							return;
						}

						// 0 = question id; 1 = m4a file url
						String[] responseParts = responseString.split(",");
						int questionNumber = 0;
						try {
							questionNumber = Integer.parseInt(responseParts[0]);
						} catch (NumberFormatException ignored) {
						}

						if (questionNumber > 0) {
							Pss2.logEvent("Upload of question " + questionNumber + " successful: " + responseParts[1]);
							File newQuestionFile = new File(HOME_DIRECTORY, String.format(QUESTION_FILE_FORMAT,
									questionNumber, questionFile
									.getName()));

							// popularity means number reuse: rename any older question/answer files with this number
							File existingQuestionFile = getLocalAudioFile(QUESTION_FILE_PATTERN, questionNumber);
							File existingAnswerFile = getLocalAudioFile(ANSWER_FILE_PATTERN, questionNumber);
							Date fileDate = new Date();
							if (existingQuestionFile != null) {
								String oldQuestionFileNewName =
										existingQuestionFile.getAbsolutePath() + "." + fileDate.getTime() + ".old";
								Pss2.logEvent(
										"Reusing existing number - renaming question " + existingQuestionFile + " to" +
												" " + oldQuestionFileNewName + " - " +
												existingQuestionFile.renameTo(new File(oldQuestionFileNewName)));
							}
							if (existingAnswerFile != null) {
								String oldAnswerFileNewName =
										existingAnswerFile.getAbsolutePath() + "." + fileDate.getTime() + ".old";
								Pss2.logEvent(
										"Reusing existing number - renaming answer " + existingAnswerFile + " to" +
												" " + oldAnswerFileNewName + " - " +
												existingAnswerFile.renameTo(new File(oldAnswerFileNewName)));
							}

							if (questionFile.renameTo(newQuestionFile)) {
								mProgressUpdater.stop(questionNumber); // ugly hack to also update number when stopping

								// callback so we can manually read the numbers in order, then finish with either
								// audio or visual confirmation
								mNumberPlaybackCallback.initialise(questionNumber,
										() -> mSoundPlayer.playSoundResource(SoundPlayer.Sound.question_received_hint,
												() -> setLedState(LedState.On)));
								mSoundPlayer.playSoundResource(SoundPlayer.Sound.question_received,
										() -> mSoundPlayer.playSoundResource(SoundPlayer.Sound.question_number_allocated, mNumberPlaybackCallback));
							} else {
								// TODO: anything else we can do? (has been sent to server, so will be answered...)
								onError("Upload file renaming failed: " + responseString);
							}
						} else {
							onError("Upload response parsing failed: " + responseString);
						}
					}
				}

				private void onError(String message) {
					Pss2.logEvent(message);
					mSoundPlayer.playSoundResource(SoundPlayer.Sound.error);
					setLedState(LedState.On);
				}
			});
		} catch (Exception e) {
			Pss2.logEvent("Upload failed: " + e.getLocalizedMessage());
			mSoundPlayer.playSoundResource(SoundPlayer.Sound.error);
			setLedState(LedState.On);
		}
	}

	private class NumberPlaybackCallback implements SoundPlayer.ResourcePlaybackCompletedCallback {
		private int[] mDigits;
		private SoundPlayer.ResourcePlaybackCompletedCallback mFinalDigitCallback;
		private int mDigitPosition;

		void initialise(int questionNumber, SoundPlayer.ResourcePlaybackCompletedCallback finalDigitCallback) {
			mDigits = String.valueOf(questionNumber).chars().map(Character::getNumericValue).toArray();
			mFinalDigitCallback = finalDigitCallback;
			mDigitPosition = 0;
		}

		@Override
		public void onPlaybackCompleted() {
			if (mDigitPosition < mDigits.length - 1) {
				mSoundPlayer.playSoundResource(SoundPlayer.getSoundForNumber(mDigits[mDigitPosition]),
						mNumberPlaybackCallback);
				mDigitPosition += 1;
			} else {
				// for the final digit, add the requested callback
				mSoundPlayer.playSoundResource(SoundPlayer.getSoundForNumber(mDigits[mDigitPosition]),
						mFinalDigitCallback);
			}
		}
	}

	private void getAndPlayAnswer(int questionNumber) {
		Pss2.logEvent("Requesting answer for question " + questionNumber);
		setLedState(LedState.Off);

		// the local hint if 1234 is entered
		if (questionNumber == 1234) {
			mProgressUpdater.stop();
			mSoundPlayer.playSoundResource(SoundPlayer.Sound.number_hint_1234, () -> {
				Pss2.logEvent("1234 answer hint playback completed (callback)");
				pssIO.clearDisplay();
				setLedState(LedState.On);
			});
			return;
		}

		// the answer is ready, and has been cached locally
		File localAnswerFile = getLocalAudioFile(ANSWER_FILE_PATTERN, questionNumber);
		if (localAnswerFile != null) {
			Pss2.logEvent("Cached answer file found: " + localAnswerFile.getAbsolutePath());
			playAnswer(localAnswerFile.getAbsolutePath());
			return;
		}

		// no local answer - double check whether it has been answered since we last checked
		try {
			Pss2.logEvent("Sending immediate server answer request for " + questionNumber);
			HttpUrl url = HttpUrl.parse(ANSWER_SERVER_URL);
			if (url == null) {
				Pss2.logEvent("Immediate server answer request URL error: " + ANSWER_SERVER_URL);
				playAnswerError();
				return;
			}

			mProgressUpdater.stop();
			mProgressUpdaterThread = new Thread(mProgressUpdater);
			mProgressUpdaterThread.start();

			HttpUrl requestUrl = url.newBuilder()
					.addQueryParameter("source", String.valueOf(mPss2Id))
					.addQueryParameter("k", SERVER_KEY)
					.addQueryParameter("id", String.valueOf(questionNumber))
					.build();
			Request request = new Request.Builder().url(requestUrl).build();
			Pss2.logEvent("Requesting URL:" + requestUrl.toString());
			getOkHttpClient().newCall(request).enqueue(new Callback() {
				@Override
				public void onFailure(Call call, IOException e) {
					Pss2.logEvent("Immediate server answer request failed: " + call + ", " + e.getLocalizedMessage());
					playAnswerError();
				}

				@Override
				public void onResponse(Call call, Response response) throws IOException {
					ResponseBody responseBody = response.body();
					if (!response.isSuccessful() || responseBody == null) {
						Pss2.logEvent("Immediate server answer response unsuccessful: " +
								(responseBody != null ? responseBody.string() : null));
						playAnswerError();
					} else {
						String responseString = responseBody.string();
						if ("unanswered".equals(responseString)) {
							// if we get here then the answer is definitely not ready
							Pss2.logEvent("Immediate server answer response not ready for " + questionNumber);
							playAnswerError();
							return;
						} else if ("error".equals(responseString)) {
							// if we get here then the question wasn't found
							Pss2.logEvent("Immediate server answer question not found for " + questionNumber);
							playQuestionNotFoundError();
							return;
						}

						// 0 = question id; 1 = m4a file url
						String[] responseParts = responseString.split(",");
						int remoteQuestionNumber = 0;
						String answerAudioFile = responseParts[1];
						try {
							remoteQuestionNumber = Integer.parseInt(responseParts[0]);
						} catch (NumberFormatException ignored) {
						}

						if (remoteQuestionNumber == questionNumber && answerAudioFile != null) {
							AnswerDownloader.downloadAnswer(questionNumber, answerAudioFile, HOME_DIRECTORY,
									new AnswerDownloader.DownloadCompletedCallback() {
								@Override
								public void onDownloadComplete(String answerLocation) {
									Pss2.logEvent("Answer download complete (playback callback)");
									playAnswer(answerLocation);
								}

								@Override
								public void onDownloadFailed() {
									Pss2.logEvent("Answer download failed (playback callback)");
									playAnswerError();
								}
							});
						} else {
							// if we get here then the answer is definitely not ready
							Pss2.logEvent(
									"Immediate server answer response question number incorrect for " + questionNumber);
							playAnswerError();
						}
					}
				}
			});
		} catch (Exception e) {
			Pss2.logEvent("Immediate server answer failed: " + e.getLocalizedMessage());
			playAnswerError();
		}
	}

	private void playAnswer(String answerLocation) {
		mProgressUpdater.stop();
		mSoundPlayer.playSoundResource(SoundPlayer.Sound.answer_ready, () -> mSoundPlayer.playAnswer(answerLocation,
				new SoundPlayer.Mp3PlaybackCompletedCallback() {
			@Override
			public void onPlaybackCompleted() {
				Pss2.logEvent("Answer playback completed (callback)");
				pssIO.clearDisplay();
				setLedState(LedState.On);
			}

			@Override
			public void onPlaybackFailed() {
				Pss2.logEvent("Answer playback failed (callback)");
				playAnswerError(); // this also sets the led state (after playing error message)
			}
		}));
	}

	private void playAnswerError() {
		mProgressUpdater.stop();
		mSoundPlayer.playSoundResource(SoundPlayer.Sound.answer_not_ready, () -> {
			pssIO.clearDisplay();
			setLedState(LedState.On);
		});
	}

	private void playQuestionNotFoundError() {
		mSoundPlayer.playSoundResource(SoundPlayer.Sound.question_number_invalid, () -> {
			pssIO.clearDisplay();
			setLedState(LedState.On);
		});
	}

	static void logEvent(String message) {
		if (mLogWriter == null) {
			try {
				mLogWriter = new PrintWriter(new FileWriter(new File(HOME_DIRECTORY, "pss2.log"), true));
			} catch (Exception e) {
				System.out.println("Error initiating log writer - exiting");
				System.exit(-1); // exit because this is something we should know about
			}
		}
		System.out.println(message);
		mLogWriter.write(
				System.currentTimeMillis() + " (" + SimpleDateFormat.getDateTimeInstance().format(new Date()) + "): " +
						message + "\n");
		mLogWriter.flush();
	}

	static OkHttpClient getOkHttpClient() {
		if (sOkHttpClient == null) {
			sOkHttpClient = new OkHttpClient.Builder().build();
		}
		return sOkHttpClient;
	}
}
