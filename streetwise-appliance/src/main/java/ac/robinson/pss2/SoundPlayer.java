package ac.robinson.pss2;

import javax.sound.sampled.*;
import java.io.IOException;
import java.net.URL;

class SoundPlayer {

	private static final String AUDIO_FILE_EXTENSION = ".wav";

	private final String mLanguageCode;

	private Clip mCurrentClip;

	public enum Sound {
		start,
		begin_question,
		question_received,
		question_number_allocated,
		question_number_invalid,
		question_received_hint,
		answer_not_ready,
		answer_ready,
		error,
		number_0,
		number_1,
		number_2,
		number_3,
		number_4,
		number_5,
		number_6,
		number_7,
		number_8,
		number_9,
		number_hint_1234
	}

	interface ResourcePlaybackCompletedCallback {
		/**
		 * Called when playback has completed
		 */
		void onPlaybackCompleted();
	}

	interface Mp3PlaybackCompletedCallback {
		/**
		 * Called when playback has completed
		 */
		void onPlaybackCompleted();

		/**
		 * Called if a playback error is encountered
		 */
		void onPlaybackFailed();
	}

	SoundPlayer(String languageCode) {
		mLanguageCode = languageCode;
	}

	private URL getSound(Sound sound) {
		URL soundUrl = getClass().getClassLoader().getResource(getResourceFilename(sound.toString()));
		if (soundUrl == null) {
			Pss2.logEvent("Warning: sound not found: " + sound);
			return getSound(Sound.error);
		}
		return soundUrl;
	}

	static Sound getSoundForNumber(int number) {
		switch (number) {
			case 0:
				return Sound.number_0;
			case 1:
				return Sound.number_1;
			case 2:
				return Sound.number_2;
			case 3:
				return Sound.number_3;
			case 4:
				return Sound.number_4;
			case 5:
				return Sound.number_5;
			case 6:
				return Sound.number_6;
			case 7:
				return Sound.number_7;
			case 8:
				return Sound.number_8;
			case 9:
				return Sound.number_9;
			default:
				Pss2.logEvent("Warning: number sound not found for " + number);
				return Sound.error;
		}
	}

	private String getResourceFilename(String resourceName) {
		return String.format(resourceName + "_%s%s", mLanguageCode, AUDIO_FILE_EXTENSION);
	}

	void playSoundResource(Sound sound) {
		playSoundResource(sound, null);
	}

	void playSoundResource(Sound sound, ResourcePlaybackCompletedCallback resourcePlaybackCompletedCallback) {
		Pss2.logEvent("Playing sound: " + sound + " (" + mLanguageCode + ")");
		try {
			if (mCurrentClip != null) {
				mCurrentClip.stop();
			}


			AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(getSound(sound));
			mCurrentClip = AudioSystem.getClip(); // TODO: should we preload and cache Clip objects?
			mCurrentClip.addLineListener(event -> {
				if (event.getType() == LineEvent.Type.STOP) { // must release so future playback can take place
					mCurrentClip.close();
					Pss2.logEvent("Finished playing sound: " + sound + " (" + mLanguageCode + ")");
					if (resourcePlaybackCompletedCallback != null) {
						resourcePlaybackCompletedCallback.onPlaybackCompleted();
					}
				}
			});
			mCurrentClip.open(audioInputStream); // note: can't set volume as no gain control - done via system instead
			mCurrentClip.start(); // asynchronous

		} catch (UnsupportedAudioFileException | IOException | LineUnavailableException e) {
			Pss2.logEvent("Error playing sound: " + sound);
			e.printStackTrace();
		}
	}

	void playAnswer(String answerLocation, Mp3PlaybackCompletedCallback mp3PlaybackCompletedCallback) {
		new Thread(() -> {
			Pss2.logEvent("Playing answer file from: " + answerLocation);
			try {
				// used to use VLC here, but for some reason it silently fails to play certain files/streams
				Process vlcProcess = Runtime.getRuntime().exec("mplayer " + answerLocation);
				int vlcResult = vlcProcess.waitFor();
				Pss2.logEvent("Finished playing answer file: " + answerLocation + " (VLC result: " + vlcResult + ")");
				mp3PlaybackCompletedCallback.onPlaybackCompleted();
			} catch (IOException | InterruptedException e) {
				Pss2.logEvent("Error: unable to play answer file: " + answerLocation);
				e.printStackTrace();
				mp3PlaybackCompletedCallback.onPlaybackFailed();
			}
		}).start();
	}
}
