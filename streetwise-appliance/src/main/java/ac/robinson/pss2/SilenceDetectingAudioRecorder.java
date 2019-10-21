package ac.robinson.pss2;

import be.tarsos.dsp.*;
import be.tarsos.dsp.io.TarsosDSPAudioFormat;
import be.tarsos.dsp.io.jvm.JVMAudioInputStream;
import be.tarsos.dsp.writer.WriterProcessor;
import de.sciss.jump3r.Main;

import javax.sound.sampled.*;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;

class SilenceDetectingAudioRecorder implements AudioProcessor {

	private static final String RAW_AUDIO_FILE_EXTENSION = ".wav";
	static final String MP3_AUDIO_FILE_EXTENSION = ".mp3";

	private static final float RECORDING_SAMPLE_RATE = 32000; // to match LAME options below
	private static final int RECORDING_SAMPLE_SIZE = 16;
	private static final int RECORDING_CHANNELS = 1;
	private static final boolean RECORDING_SIGNED = true;
	private static final boolean RECORDING_BIG_ENDIAN = false;

	private static final int DSP_BUFFER_SIZE = 512;
	private static final int DSP_OVERLAP = 0;
	private static final int DSP_SILENCE_TIMEOUT_MS = Pss2.SILENCE_TIMEOUT_MS;
	private static final int DSP_DURATION_MINIMUM_MS = Pss2.DURATION_MINIMUM_MS;
	private static final int DSP_DURATION_MAXIMUM_MS = Pss2.DURATION_MAXIMUM_MS;

	private final File mOutputDirectory;
	private File mRawOutputFile;

	private AudioDispatcher mDispatcher;
	private Mixer mMixer;

	private SilenceDetector mSilenceDetector;
	private int mCountSilentSamplesDetected;
	private int mSilenceTimeoutBufferCount;
	private int mCountTotalSamplesRecorded;
	private int mDurationMinimumBufferCount;
	private int mDurationMaximumBufferCount;

	private PssIO mIo;

	private RecordingCompletedCallback mRecordingCompletedCallback;
	private double mSilenceThreshold;

	interface RecordingCompletedCallback {
		/**
		 * Called when recording has started (i.e., after initialisation etc)
		 */
		void onRecordingStarted();

		/**
		 * Called when audio has finished recording, and we are now creating the mp3
		 */
		void onEncodingStarted();

		/**
		 * Called when recording has completed (i.e., both saved to wav and encoded to mp3)
		 */
		void onRecordingCompleted(File outputFile);

		/**
		 * Called if recording fails
		 */
		void onRecordingFailed();
	}

	SilenceDetectingAudioRecorder(File outputDirectory, PssIO io, Mixer mixer) {
		mOutputDirectory = outputDirectory;
		mIo = io;

		mMixer = mixer;
		RuntimeException mixerError = null;
	}

	boolean startRecording(double silenceThreshold, RecordingCompletedCallback recordingCompletedCallback) {
		if (mMixer == null || mDispatcher != null) {
			return false;
		}

		mIo.startOscilloscope();

		mRecordingCompletedCallback = recordingCompletedCallback;
		mSilenceThreshold = silenceThreshold;

		Date fileDate = new Date();
		mRawOutputFile = new File(mOutputDirectory, new SimpleDateFormat(
				"'" + fileDate.getTime() + "'_yyyy-MM-dd_HH-mm-ss'" + RAW_AUDIO_FILE_EXTENSION + "'").format(fileDate));
		//noinspection ResultOfMethodCallIgnored // we don't mind if this fails - we overwrite anyway
		mRawOutputFile.delete();

		float bufferToMsFactor = (((RECORDING_SAMPLE_RATE * RECORDING_CHANNELS) / DSP_BUFFER_SIZE) / 1000);
		mCountSilentSamplesDetected = 0;
		mSilenceTimeoutBufferCount = Math.round(DSP_SILENCE_TIMEOUT_MS * bufferToMsFactor);
		mCountTotalSamplesRecorded = 0;
		mDurationMinimumBufferCount = Math.round(DSP_DURATION_MINIMUM_MS * bufferToMsFactor);
		mDurationMaximumBufferCount = Math.round(DSP_DURATION_MAXIMUM_MS * bufferToMsFactor);

		try {
			AudioFormat dataLineFormat = new AudioFormat(RECORDING_SAMPLE_RATE, RECORDING_SAMPLE_SIZE,
					RECORDING_CHANNELS, RECORDING_SIGNED, RECORDING_BIG_ENDIAN);
			DataLine.Info dataLineInfo = new DataLine.Info(TargetDataLine.class, dataLineFormat);
			TargetDataLine targetDataLine = (TargetDataLine) mMixer.getLine(dataLineInfo);
			targetDataLine.open(dataLineFormat, DSP_BUFFER_SIZE);
			targetDataLine.start();
			AudioInputStream stream = new AudioInputStream(targetDataLine);

			TarsosDSPAudioFormat recordingFormat = new TarsosDSPAudioFormat(RECORDING_SAMPLE_RATE,
					RECORDING_SAMPLE_SIZE, RECORDING_CHANNELS, RECORDING_SIGNED, RECORDING_BIG_ENDIAN);
			AudioProcessor recordingProcessor = new WriterProcessor(recordingFormat,
					new RandomAccessFile(mRawOutputFile, "rw"));

			mSilenceDetector = new SilenceDetector(mSilenceThreshold, false);

			JVMAudioInputStream audioStream = new JVMAudioInputStream(stream);
			mDispatcher = new AudioDispatcher(audioStream, DSP_BUFFER_SIZE, DSP_OVERLAP);
			mDispatcher.addAudioProcessor(recordingProcessor);
			mDispatcher.addAudioProcessor(mSilenceDetector);
			mDispatcher.addAudioProcessor(SilenceDetectingAudioRecorder.this);
			mDispatcher.addAudioProcessor(new Oscilloscope(mIo));

			new Thread(mDispatcher, "SilenceDetectingAudioRecorder").start();
			mRecordingCompletedCallback.onRecordingStarted();
			return true;

		} catch (LineUnavailableException | FileNotFoundException e) {
			Pss2.logEvent("Unable to initialise audio recording");
			e.printStackTrace();
			return false;
		}
	}

	private void stopRecording() {
		mDispatcher.stop();
		mIo.stopOscilloscope();
		Pss2.logEvent("Stopped recording; encoding to mp3");
		mRecordingCompletedCallback.onEncodingStarted();

		File outputMp3 = new File(mOutputDirectory, mRawOutputFile.getName()
				.replace(RAW_AUDIO_FILE_EXTENSION, MP3_AUDIO_FILE_EXTENSION));
		//noinspection ResultOfMethodCallIgnored // we don't mind if this fails - we overwrite anyway
		outputMp3.delete();
		String[] mp3Args = {
				// TODO: check whether voice preset actually produces the smallest file sizes
				"--preset", "voice", /* https://svn.code.sf.net/p/lame/svn/trunk/lame/USAGE */
				mRawOutputFile.getAbsolutePath(), outputMp3.getAbsolutePath()
		};

		// TODO: for speed, use VLC or ffmpeg to do this?
		Main mp3Encoder = new Main();
		// mp3Encoder.getSupport().addPropertyChangeListener(event -> System.out.print(event.getNewValue()));
		try {
			mp3Encoder.run(mp3Args); // note: this is blocking
			//noinspection ResultOfMethodCallIgnored // we don't mind if this fails - we overwrite anyway
			mRawOutputFile.delete();
			mDispatcher = null;
			Pss2.logEvent("Mp3 recorded to " + outputMp3.getAbsolutePath());
			mRecordingCompletedCallback.onRecordingCompleted(outputMp3);
		} catch (Exception e) {
			mDispatcher = null;
			Pss2.logEvent("Error processing mp3 " + e);
			e.printStackTrace();
			mRecordingCompletedCallback.onRecordingFailed();
		}
	}

	@Override
	public boolean process(AudioEvent audioEvent) {
		if (mSilenceDetector.currentSPL() > mSilenceThreshold) {
			mCountSilentSamplesDetected = 0;
			//Pss2.logEvent(
			//		"Silence: " + mCountSilentSamplesDetected + "; sound detected at:" + System.currentTimeMillis() +
			//				", " + (int) (mSilenceDetector.currentSPL()) + "dB SPL");
		} else {
			mCountSilentSamplesDetected += 1;
			//Pss2.logEvent(
			//		"Silence: " + mCountSilentSamplesDetected + "; silence at:" + System.currentTimeMillis() + ", " +
			//				(int) (mSilenceDetector.currentSPL()) + "dB SPL");

			if (mCountSilentSamplesDetected >= mSilenceTimeoutBufferCount &&
					mCountTotalSamplesRecorded >= mDurationMinimumBufferCount) {
				Pss2.logEvent("Stopping recording after " + DSP_SILENCE_TIMEOUT_MS + "ms of silence (" +
						mSilenceTimeoutBufferCount + ")");
				stopRecording();
			}
		}

		mCountTotalSamplesRecorded += 1;
		if (mCountTotalSamplesRecorded >= mDurationMaximumBufferCount) {
			Pss2.logEvent("Stopping recording after " + DSP_DURATION_MAXIMUM_MS + "ms of recording (" +
					mSilenceTimeoutBufferCount + ")");
			stopRecording();
		}

		return true;
	}

	@Override
	public void processingFinished() {
		// nothing to do
	}
}
