package ac.robinson.streetwise;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.util.concurrent.ArrayBlockingQueue;

/*
 * Adapted from the audioonly branch of GitHub repository HWEncoderExperiments (no license given).
 * See: https://github.com/OnlyInAmerica/HWEncoderExperiments/blob/audioonly/HWEncoderExperiments/src/main/java/net
 * /openwatch/hwencoderexperiments/AudioSoftwarePoller.java
 *
 * This class polls audio from the microphone and feeds an AudioEncoder. Audio buffers are recycled between this class
 * and the AudioEncoder. The class also notifies the {@link AudioSoftwarePoller.VoiceCallback} when voice (or any
 * sound) is heard.
 *
 * Usage:
 *
 * 1. AudioSoftwarePoller recorder = new AudioSoftwarePoller();
 * 1a (optional): recorder.setSamplesPerFrame(NUM_SAMPLES_PER_CODEC_FRAME)
 * 2. recorder.setAudioEncoder(myAudioEncoder)
 * 2. recorder.startPolling();
 * 3. recorder.stopPolling();
 *
 * The recorded audio format is always {@link AudioFormat#ENCODING_PCM_16BIT} and {@link AudioFormat#CHANNEL_IN_MONO}.
 * This class will automatically pick the right sample rate for the device. Use {@link #getSampleRate()} to get the
 * selected value.
 */
@SuppressWarnings("SpellCheckingInspection")
class AudioSoftwarePoller {
	private static final String TAG = "AudioSoftwarePoller";

	// audio recording configuration
	static final int SAMPLE_RATE = 16000; // or 22050 or 44100
	private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
	private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
	private static final int FRAMES_PER_BUFFER = 24; // 1 sec @ 1024 samples/frame (aac)

	private AudioEncoder mAudioEncoder;
	private RecorderTask mRecorderTask = new RecorderTask();
	private static boolean sIsRecording = false;

	void setAudioEncoder(AudioEncoder audioEncoder) {
		mAudioEncoder = audioEncoder;
	}

	/**
	 * Set the number of samples per frame (Default is 2048). Call this before startPolling().
	 * The output of emptyBuffer() will be equal to, or a multiple of, this value.
	 *
	 * @param samplesPerFrame The desired audio frame size in samples.
	 */
	void setSamplesPerFrame(int samplesPerFrame) {
		if (!sIsRecording) {
			mRecorderTask.mSamplesPerFrame = samplesPerFrame;
		}
	}

	/**
	 * The number of microseconds represented by each frame, calculated with the sampling rate and samples per frame
	 */
	long getMicrosecondsPerFrame() {
		return (SAMPLE_RATE / mRecorderTask.mSamplesPerFrame) * 1000000; // was cached as static, but not used
	}

	int getSampleRate() {
		return SAMPLE_RATE;
	}

	void recycleInputBuffer(byte[] buffer) {
		mRecorderTask.mDataBuffer.offer(buffer);
	}

	/**
	 * Begin polling audio and transferring it to the buffer. Call this before emptyBuffer().
	 */
	void startPolling() {
		new Thread(mRecorderTask).start();
	}

	/**
	 * Stop polling audio.
	 */
	void stopPolling() {
		sIsRecording = false; // will stop recording after next sample received by recorder task
	}

	public class RecorderTask implements Runnable {
		private static final int BUFFER_LIMIT = 50; // number of buffers used (half initially created, up to half more)
		private int mSamplesPerFrame = 2048; // codec-specific

		private ArrayBlockingQueue<byte[]> mDataBuffer = new ArrayBlockingQueue<>(BUFFER_LIMIT); // buffer queue

		@Override
		public void run() {
			// ensure buffer is adequately sized for the AudioRecord object to initialise
			// TODO: should we try different sample rates and loop with getMinBufferSize? (see commented code below)
			int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
			int bufferSize = mSamplesPerFrame * FRAMES_PER_BUFFER;
			if (bufferSize < minBufferSize) {
				bufferSize = ((minBufferSize / mSamplesPerFrame) + 1) * mSamplesPerFrame * 2;
			}
			for (int x = 0; x < BUFFER_LIMIT / 2; x++) {
				mDataBuffer.add(new byte[mSamplesPerFrame]);
			}

			AudioRecord audioRecorder;
			Log.d(TAG, "Initialising with sample rate " + SAMPLE_RATE + ", channel " + CHANNEL_CONFIG + ", encoding " +
					AUDIO_FORMAT + ", size in bytes " + bufferSize);
			audioRecorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT,
					bufferSize);

			long audioTimeNs;
			int readResult;
			int buffersAdded = 0;
			int readErrors = 0;

			Log.i(TAG, "Recording starting");
			audioRecorder.startRecording();
			sIsRecording = true;
			while (sIsRecording) {

				// create / reuse buffers
				byte[] currentBuffer;
				if (mDataBuffer.isEmpty()) {
					currentBuffer = new byte[mSamplesPerFrame];
					buffersAdded += 1;
					Log.e(TAG, "Audio buffer empty - added new buffer " + buffersAdded);
					if (buffersAdded >= BUFFER_LIMIT / 2) {
						Log.e(TAG, "Cancelling recording due to audio buffer bug");
						sIsRecording = false; // stop - don't fill up memory if we hit this bug
					}
				} else {
					currentBuffer = mDataBuffer.poll();
				}

				// get the actual audio
				audioTimeNs = System.nanoTime();
				readResult = audioRecorder.read(currentBuffer, 0, mSamplesPerFrame);
				if (readResult == AudioRecord.ERROR_BAD_VALUE || readResult == AudioRecord.ERROR_INVALID_OPERATION) {
					readErrors += 1;
					Log.e(TAG, "Read error: " + readErrors);
					if (readErrors >= 10) {
						Log.e(TAG, "Cancelling recording due to multiple read errors");
						sIsRecording = false;
					}
					continue;
				}

				// encode this buffer
				if (mAudioEncoder != null) {
					mAudioEncoder.offerAudioEncoder(currentBuffer, audioTimeNs);
				}
			}

			audioRecorder.release();
			Log.i(TAG, "Stopped recording");
		}
	}
}
