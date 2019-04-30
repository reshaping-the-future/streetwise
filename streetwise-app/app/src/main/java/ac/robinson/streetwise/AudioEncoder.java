package ac.robinson.streetwise;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/*
 * Adapted from the audioonly branch of GitHub repository HWEncoderExperiments (no license given).
 * See: https://github.com/OnlyInAmerica/HWEncoderExperiments/blob/audioonly/HWEncoderExperiments/src/main/java/net
 * /openwatch/hwencoderexperiments/AudioEncoder.java
 *
 * Created by davidbrodsky on 9/12/13.
 * Enormous thanks to Andrew McFadden for his MediaCodec examples!
 * Adapted from http://bigflake.com/mediacodec/CameraToMpegTest.java.txt
 */
@SuppressWarnings("SpellCheckingInspection")
public class AudioEncoder {
	private static final String TAG = "AudioEncoder";

	private static final String AUDIO_MIME_TYPE = "audio/mp4a-latm";
	private static final int TOTAL_NUM_TRACKS = 1;

	private static final boolean VERBOSE = false;

	private static long sAudioBytesReceived = 0;
	private static int sNumTracksAdded = 0;

	private boolean mEosReceived = false;
	private boolean mEosSentToAudioEncoder = false;
	private boolean mStopReceived = false;
	private long mAudioStartTime = 0;

	private int mTotalInputAudioFrameCount = 0; // testing
	private int mEncodingServiceQueueLength = 0;

	private MediaCodec mAudioEncoder;
	private TrackIndex mAudioTrackIndex = new TrackIndex();

	private MediaMuxer mMuxer;
	private boolean mMuxerStarted;
	private MediaCodec.BufferInfo mAudioBufferInfo;
	private ExecutorService mEncodingService = Executors.newSingleThreadExecutor(); // re-use encoding service

	private AudioSoftwarePoller mAudioSoftwarePoller;

	private File mOutputDirectory;
	private File mOutputFile;

	// TODO: fix this
	private MainActivity.Question mQuestion;
	private MainActivity.QuestionAdapter.ViewHolder mViewHolder;

	private EncoderCallback mEncoderCallback;

	static abstract class EncoderCallback {
		/**
		 * Called when encoding has completed (i.e., the output file will not be edited any more)
		 */
		void onEncodingCompleted(File outputFile) {
		}
	}

	AudioEncoder(File outputDirectory, MainActivity.Question question,
				 MainActivity.QuestionAdapter.ViewHolder viewHolder) {
		mOutputDirectory = outputDirectory;
		mQuestion = question;
		mViewHolder = viewHolder;
	}

	MainActivity.Question getQuestion() {
		return mQuestion;
	}

	MainActivity.QuestionAdapter.ViewHolder getViewHolder() {
		return mViewHolder;
	}

	void setEncodingCompletedCallback(EncoderCallback callback) {
		mEncoderCallback = callback;
	}

	void setAudioSoftwarePoller(AudioSoftwarePoller audioSoftwarePoller) {
		mAudioSoftwarePoller = audioSoftwarePoller;
	}

	void prepare() {
		sAudioBytesReceived = 0;
		sNumTracksAdded = 0;

		mEosReceived = false;
		mEosSentToAudioEncoder = false;
		mStopReceived = false;

		// TODO: improve this - pass name on creation?
		mOutputFile = new File(mOutputDirectory,
				String.valueOf(System.currentTimeMillis()) + MainActivity.AUDIO_FILE_EXTENSION);
		//noinspection ResultOfMethodCallIgnored
		mOutputFile.delete(); // just in case
		Log.d(TAG, "Saving audio to: " + mOutputFile.getAbsolutePath());

		mAudioBufferInfo = new MediaCodec.BufferInfo();

		MediaFormat audioFormat = new MediaFormat();
		audioFormat.setString(MediaFormat.KEY_MIME, AUDIO_MIME_TYPE);
		audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
		audioFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, AudioSoftwarePoller.SAMPLE_RATE);
		audioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
		audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
		audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

		try {
			mAudioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE);
		} catch (IOException e) {
			e.printStackTrace();
			throw new RuntimeException();
		}
		mAudioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
		mAudioEncoder.start();

		try {
			mMuxer = new MediaMuxer(mOutputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
		} catch (IOException ioe) {
			throw new RuntimeException("MediaMuxer creation failed", ioe);
		}
	}

	File stop() {
		if (!mEncodingService.isShutdown()) {
			mEncodingService.submit(new EncoderTask(this, EncoderTaskType.FINALIZE_ENCODER));
		} else {
			Log.e(TAG, "Encoding already stopped - callback will not follow"); // TODO: does this ever happen?
		}
		return mOutputFile;
	}

	/**
	 * Called from encoding service
	 */
	private void _stop() {
		mStopReceived = true;
		mEosReceived = true;
		logStatistics();
	}

	private void closeEncoderAndMuxer(MediaCodec encoder, MediaCodec.BufferInfo bufferInfo, TrackIndex trackIndex) {
		drainEncoder(encoder, bufferInfo, trackIndex, true);
		try {
			encoder.stop();
			encoder.release();
			closeMuxer();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@SuppressWarnings("unused")
	public void closeEncoder(MediaCodec encoder, MediaCodec.BufferInfo bufferInfo, TrackIndex trackIndex) {
		drainEncoder(encoder, bufferInfo, trackIndex, true);
		try {
			encoder.stop();
			encoder.release();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void closeMuxer() {
		mMuxer.stop();
		mMuxer.release();
		mMuxer = null;
		mMuxerStarted = false;
	}

	/**
	 * Temp restriction: Always call after offerVideoEncoder
	 */
	void offerAudioEncoder(byte[] input, long presentationTimeStampNs) {
		if (!mEncodingService.isShutdown()) {
			//long thisFrameTime = (mPresentationTimeNs == 0) ? System.nanoTime() : mPresentationTimeNs;
			mEncodingService.submit(new EncoderTask(this, input, presentationTimeStampNs));
			mEncodingServiceQueueLength++;
		}
	}

	/**
	 * Temp restriction: Always call after _offerVideoEncoder
	 */
	private void _offerAudioEncoder(byte[] input, long presentationTimeNs) {
		if (sAudioBytesReceived == 0) {
			mAudioStartTime = presentationTimeNs;
		}
		mTotalInputAudioFrameCount++;
		sAudioBytesReceived += input.length;
		if (mEosSentToAudioEncoder && mStopReceived) {
			logStatistics();
			if (mEosReceived) {
				Log.i(TAG, "EOS received in offerAudioEncoder");
				closeEncoderAndMuxer(mAudioEncoder, mAudioBufferInfo, mAudioTrackIndex);
				mEosSentToAudioEncoder = true;
				if (!mStopReceived) {
					prepare(); // swap mEncoder
				} else {
					Log.i(TAG, "Stopping Encoding Service");
					mEncodingService.shutdown();
					if (mEncoderCallback != null) {
						mEncoderCallback.onEncodingCompleted(mOutputFile);
					}
				}
			}
			return;
		}

		// transfer previously encoded data to muxer
		drainEncoder(mAudioEncoder, mAudioBufferInfo, mAudioTrackIndex, false);

		// send current frame data to mEncoder
		try {
			ByteBuffer[] inputBuffers = mAudioEncoder.getInputBuffers();
			int inputBufferIndex = mAudioEncoder.dequeueInputBuffer(-1);
			if (inputBufferIndex >= 0) {
				ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
				inputBuffer.clear();
				inputBuffer.put(input);
				if (mAudioSoftwarePoller != null) {
					mAudioSoftwarePoller.recycleInputBuffer(input);
				}
				long presentationTimeUs = (presentationTimeNs - mAudioStartTime) / 1000;
				if (mEosReceived) {
					Log.i(TAG, "EOS received in offerEncoder");
					mAudioEncoder.queueInputBuffer(inputBufferIndex, 0, input.length, presentationTimeUs,
							MediaCodec.BUFFER_FLAG_END_OF_STREAM);
					closeEncoderAndMuxer(mAudioEncoder, mAudioBufferInfo, mAudioTrackIndex); //
					// always called after video, so safe to close muxer
					mEosSentToAudioEncoder = true;
					if (mStopReceived) {
						Log.i(TAG, "Stopping encoding service");
						mEncodingService.shutdown();
						if (mEncoderCallback != null) {
							mEncoderCallback.onEncodingCompleted(mOutputFile);
						}
					}
				} else {
					mAudioEncoder.queueInputBuffer(inputBufferIndex, 0, input.length, presentationTimeUs, 0);
				}
			}
		} catch (Throwable t) {
			Log.e(TAG, "_offerAudioEncoder exception");
			t.printStackTrace();
		}
	}

	/**
	 * Extracts all pending data from the mEncoder and forwards it to the muxer.
	 * <p/>
	 * If endOfStream is not set, this returns when there is no more data to drain.  If it
	 * is set, we send EOS to the mEncoder, and then iterate until we see EOS on the output.
	 * Calling this with endOfStream set should be done once, right before stopping the muxer.
	 * <p/>
	 * We're just using the muxer to get a .mp4 file (instead of a raw H.264 stream).  We're
	 * not recording audio.
	 */
	private void drainEncoder(MediaCodec encoder, MediaCodec.BufferInfo bufferInfo, TrackIndex trackIndex,
							  boolean endOfStream) {
		final int TIMEOUT_USEC = 100;
		if (VERBOSE) {
			Log.d(TAG, "drainEncoder(" + endOfStream + ")");
		}
		ByteBuffer[] encoderOutputBuffers = encoder.getOutputBuffers();
		while (true) {
			int encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
			if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
				// no output available yet
				if (!endOfStream) {
					break; // out of while
				} else {
					if (VERBOSE) {
						Log.d(TAG, "no output available, spinning to await EOS");
					}
				}
			} else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
				// not expected for an mEncoder
				encoderOutputBuffers = encoder.getOutputBuffers();
			} else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
				// should happen before receiving buffers, and should only happen once
				if (mMuxerStarted) {
					throw new RuntimeException("Format changed after muxer started");
				}
				MediaFormat newFormat = encoder.getOutputFormat();

				// now that we have the Magic Goodies, start the muxer
				trackIndex.index = mMuxer.addTrack(newFormat);
				sNumTracksAdded++;
				Log.d(TAG, "Encoder output format changed: " + newFormat + ". Added track index: " + trackIndex.index);
				if (sNumTracksAdded == TOTAL_NUM_TRACKS) {
					mMuxer.start();
					mMuxerStarted = true;
					Log.i(TAG, "All tracks added - muxer started");
				}

			} else if (encoderStatus < 0) {
				Log.w(TAG, "Unexpected result from mEncoder.dequeueOutputBuffer: " + encoderStatus); // ignore it
			} else {
				ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
				if (encodedData == null) {
					throw new RuntimeException("EncoderOutputBuffer " + encoderStatus + " was null");
				}

				if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
					// The codec config data was pulled out and fed to the muxer when we got
					// the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
					if (VERBOSE) {
						Log.d(TAG, "Ignoring BUFFER_FLAG_CODEC_CONFIG");
					}
					bufferInfo.size = 0;
				}

				if (bufferInfo.size != 0) {
					if (!mMuxerStarted) {
						throw new RuntimeException("Muxer hasn't started");
					}

					// adjust the ByteBuffer values to match BufferInfo (not needed?)
					encodedData.position(bufferInfo.offset);
					encodedData.limit(bufferInfo.offset + bufferInfo.size);
					mMuxer.writeSampleData(trackIndex.index, encodedData, bufferInfo);
				}

				encoder.releaseOutputBuffer(encoderStatus, false);

				if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
					if (!endOfStream) {
						Log.w(TAG, "Reached end of stream unexpectedly");
					} else {
						if (VERBOSE) {
							Log.d(TAG, "End of stream reached");
						}
					}
					break; // out of while
				}
			}
		}
	}

	private void logStatistics() {
		Log.i(TAG + "-Stats",
				"Audio frames input: " + mTotalInputAudioFrameCount + ", output: totalOutputAudioFrameCount");
	}

	enum EncoderTaskType {
		ENCODE_FRAME, /*SHIFT_ENCODER,*/
		FINALIZE_ENCODER
	}

	// Can't pass an int by reference in Java...
	static class TrackIndex {
		int index = 0;
	}

	private class EncoderTask implements Runnable {
		private static final String TAG = "encoderTask";
		boolean mIsInitialized = false;
		long mPresentationTimeNs;
		private AudioEncoder mEncoder;
		private EncoderTaskType mTaskType;
		private byte[] mAudioData;

		EncoderTask(AudioEncoder encoder, EncoderTaskType type) {
			setEncoder(encoder);
			mTaskType = type;
			switch (type) {
				case ENCODE_FRAME:
					break;
				/*
				case SHIFT_ENCODER:
                    setShiftEncoderParams();
                    break;
                */
				case FINALIZE_ENCODER:
					setFinalizeEncoderParams();
					break;
			}
		}

		EncoderTask(AudioEncoder encoder, byte[] audio_data, long pts) {
			setEncoder(encoder);
			setEncodeFrameParams(audio_data, pts);
		}

		@SuppressWarnings("unused")
		public EncoderTask(AudioEncoder encoder) {
			setEncoder(encoder);
			setFinalizeEncoderParams();
		}

		private void setEncoder(AudioEncoder encoder) {
			mEncoder = encoder;
		}

		private void setFinalizeEncoderParams() {
			mIsInitialized = true;
		}

		private void setEncodeFrameParams(byte[] audioData, long pts) {
			mAudioData = audioData;
			mPresentationTimeNs = pts;

			mIsInitialized = true;
			mTaskType = EncoderTaskType.ENCODE_FRAME;
		}

		private void encodeFrame() {
			if (mEncoder != null && mAudioData != null) {
				mEncoder._offerAudioEncoder(mAudioData, mPresentationTimeNs);
				mAudioData = null;
			}
		}

		private void finalizeEncoder() {
			mEncoder._stop();
		}

		@Override
		public void run() {
			if (mIsInitialized) {
				switch (mTaskType) {
					case ENCODE_FRAME:
						encodeFrame();
						break;
					/*
					case SHIFT_ENCODER:
                        shiftEncoder();
                        break;
                    */
					case FINALIZE_ENCODER:
						finalizeEncoder();
						break;
				}
				// prevent multiple execution of same task
				mIsInitialized = false;
				mEncodingServiceQueueLength -= 1;
				if (VERBOSE) {
					Log.i(TAG, "EncodingService Queue length: " + mEncodingServiceQueueLength);
				}
			} else {
				Log.e(TAG, "run() called but EncoderTask not initialized");
			}
		}
	}
}
