package ac.robinson.streetwise;

import android.Manifest;
import android.app.NotificationManager;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class MainActivity extends AppCompatActivity {

	private static final String TAG = "StreetWise-Answers";
	private static final String OUTPUT_DIRECTORY = "StreetWise-Answers";

	private static final int REQUEST_PERMISSIONS = 1;

	private static final int BUTTON_DEBOUNCE_TIME = 250; // milliseconds

	private int mApiIdentifier; // used in API to attribute questions / requests correctly

	// for recording audio
	private AudioEncoder mEncoder;
	private AudioSoftwarePoller mAudioPoller;
	private File mOutputDirectory; // static so we can share logger between classes
	private ProgressDialog mProgressDialog;

	// for file uploads/downloads
	private OkHttpClient mOkHttpClient;
	private static String SERVER_KEY;
	private static String FILE_SERVER_URL;
	private static String TASKS_SERVER_URL;
	private static String RESERVE_SERVER_URL;

	// audio recording - local files are generally named as: [our id]-[type]-[recording start time].m4a
	public static final String AUDIO_FILE_EXTENSION = ".m4a"; // including the dot - note: *copy to pattern too*
	public static final String ANSWER_FILE_FORMAT = "%d-answer-%d%s"; // time = downloaded


	private ViewSavingMediaPlayer mQuestionMediaPlayer;
	private ViewSavingMediaPlayer mAnswerMediaPlayer;

	private ArrayList<Question> mQuestions;

	// views / adapters
	private StoppableListView mListView;
	private QuestionAdapter mQuestionAdapter;
	private ProgressBar mLoadingProgress;

	public class Question implements Comparable<Question> {
		int mId;
		String mQuestionFile;
		File mAnswerFile;

		Question(int id, String voiceFile) {
			mId = id;
			mQuestionFile = voiceFile;
		}

		@Override
		public int compareTo(@NonNull Question question) {
			if (mId == question.mId) {
				return 0;
			}
			return mId < question.mId ? -1 : 1;
		}
	}

	class ViewSavingMediaPlayer extends MediaPlayer {
		private QuestionAdapter.ViewHolder mViewHolder;

		ViewSavingMediaPlayer(QuestionAdapter.ViewHolder viewHolder) {
			mViewHolder = viewHolder;
		}
	}

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		SERVER_KEY = getString(R.string.server_key);
		FILE_SERVER_URL = String.format("%s%s", getString(R.string.server_url), "answer.php");
		TASKS_SERVER_URL = String.format("%s%s", getString(R.string.server_url), "responses.php");
		RESERVE_SERVER_URL = String.format("%s%s", getString(R.string.server_url), "reserve.php");

		mOutputDirectory = new File(Environment.getExternalStorageDirectory(), OUTPUT_DIRECTORY);

		mListView = findViewById(R.id.question_list);
		mLoadingProgress = findViewById(R.id.loading_progress);

		findViewById(R.id.refresh_button).setOnClickListener(mButtonClickListener);

		((EditText) findViewById(R.id.login)).setOnEditorActionListener(new TextView.OnEditorActionListener() {
			@Override
			public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
				if (actionId == EditorInfo.IME_ACTION_GO) {
					return checkLoginDetails(v);
				}
				return false;
			}
		});

		mOkHttpClient = StreetWiseAnswersApplication.getOkHttpClient();
		LocalBroadcastManager.getInstance(this)
				.registerReceiver(mMessageReceiver,
						new IntentFilter(StreetWiseFirebaseMessagingService.MESSAGE_RECEIVED));
	}

	@Override
	protected void onStart() {
		super.onStart();
		initialise();
	}

	@Override
	protected void onResume() {
		super.onResume();
		NotificationManager notificationManager =
				(NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
		if (notificationManager != null) {
			notificationManager.cancelAll();
		}
	}

	@Override
	protected void onDestroy() {
		stopRecording(null);
		LocalBroadcastManager.getInstance(this).unregisterReceiver(mMessageReceiver);
		super.onDestroy();
	}

	private void initialise() {
		if (checkHasPermissions() && checkHasLogin()) {
			if (!mOutputDirectory.mkdirs() && !mOutputDirectory.exists()) {
				Toast.makeText(MainActivity.this, R.string.external_storage_permission_message, Toast.LENGTH_LONG)
						.show();
				finish();
			} else {
				loadLatestQuestions();
			}
		}
	}

	private BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (checkHasPermissions() && checkHasLogin()) {
				Snackbar.make(findViewById(R.id.layout_root), R.string.questions_available, Snackbar.LENGTH_INDEFINITE)
						.setAction(R.string.refresh, new View.OnClickListener() {
							@Override
							public void onClick(View v) {
								initialise();
							}
						})
						.show();
			}
		}
	};

	public void handleLogin(View view) {
		switch (view.getId()) {
			case R.id.login_label:
				checkLoginDetails((TextView) findViewById(R.id.login));
		}
	}

	private boolean checkLoginDetails(TextView loginView) {
		if (getString(R.string.app_password).equals(loginView.getText().toString())) {
			try {
				mApiIdentifier = Integer.parseInt(((TextView) findViewById(R.id.source)).getText().toString());
			} catch (NumberFormatException e) {
				return false;
			}

			SharedPreferences preferences = getPreferences(Context.MODE_PRIVATE);
			SharedPreferences.Editor editor = preferences.edit();
			editor.putString("login", getString(R.string.app_password));
			editor.putInt("source", mApiIdentifier);
			editor.apply();

			InputMethodManager inputMethodManager =
					(InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
			if (inputMethodManager != null) {
				inputMethodManager.hideSoftInputFromWindow(loginView.getWindowToken(), 0);
			}

			findViewById(R.id.login_frame).setVisibility(View.GONE);
			initialise();
			return true;
		}
		return false;
	}

	private DebouncedOnClickListener mButtonClickListener = new DebouncedOnClickListener(BUTTON_DEBOUNCE_TIME) {
		@Override
		public void onDebouncedClick(View view) {
			switch (view.getId()) {
				case R.id.refresh_button:
					loadLatestQuestions();
					break;

				case R.id.question_id:
				case R.id.play_button:
				case R.id.play_progress:
					QuestionAdapter.ViewHolder playViewHolder = getViewHolder(view);
					if (playViewHolder.mIsPlaybackActive) {
						cleanupPreviousMediaPlayer();
					} else {
						Question currentQuestion = mQuestionAdapter.getItem(mListView.getPositionForView(view));
						if (currentQuestion != null) {
							playQuestion(currentQuestion.mQuestionFile, playViewHolder);
						}
					}
					break;

				case R.id.add_answer_button:
				case R.id.add_answer_progress:
					QuestionAdapter.ViewHolder answerViewHolder = getViewHolder(view);
					if (answerViewHolder.mIsRecordingActive) {
						stopRecording(answerViewHolder);
					} else {
						Question currentQuestion = mQuestionAdapter.getItem(mListView.getPositionForView(view));
						if (currentQuestion != null && currentQuestion.mAnswerFile != null) {
							playAnswer(currentQuestion.mAnswerFile.getAbsolutePath(), answerViewHolder);
						} else {
							startRecording(currentQuestion, answerViewHolder);
						}
					}
					break;

				case R.id.cancel_answer_button:
					cancelAnswerPlayback(null);
					QuestionAdapter.ViewHolder cancelAnswerViewHolder = getViewHolder(view);
					Question currentQuestion = mQuestionAdapter.getItem(mListView.getPositionForView(view));
					if (currentQuestion != null) {
						currentQuestion.mAnswerFile = null;
					}
					cancelAnswerViewHolder.mAddAnswerButton.setText(R.string.answer);
					cancelAnswerViewHolder.mAddAnswerButton.setCompoundDrawablesWithIntrinsicBounds(0, 0,
							R.drawable.ic_fiber_manual_record_red_500_36dp, 0);
					cancelAnswerViewHolder.mSubmitView.setVisibility(View.GONE);
					mAudioPoller = null;
					mEncoder = null;
					break;

				case R.id.submit_answer_button:
					cancelAnswerPlayback(null);
					QuestionAdapter.ViewHolder submitAnswerViewHolder = getViewHolder(view);
					Question currentAnswerQuestion = mQuestionAdapter.getItem(mListView.getPositionForView(view));
					if (currentAnswerQuestion != null) {
						submitAnswerViewHolder.mSubmitAnswerButton.setVisibility(View.GONE);
						submitAnswerViewHolder.mCancelAnswerButton.setVisibility(View.GONE);
						submitAnswerViewHolder.mSubmitAnswerProgress.setVisibility(View.VISIBLE);
						uploadFile(currentAnswerQuestion.mAnswerFile, currentAnswerQuestion);
					}
					break;

				default:
					break;
			}
		}
	};

	private View.OnClickListener mEmptySnackbarDismissListener = new View.OnClickListener() {
		@Override
		public void onClick(View view) {
			// don't need any actual action here as dismiss is called automatically... but we do need a listener so
			// that the button is actually shown
		}
	};

	private void loadLatestQuestions() {
		if (mQuestionMediaPlayer != null || mEncoder != null || mAudioPoller != null) {
			Snackbar.make(findViewById(R.id.layout_root), R.string.complete_answer_warning_loading,
					Snackbar.LENGTH_SHORT)
					.setAction(R.string.dismiss, mEmptySnackbarDismissListener)
					.show();
			return;
		}

		mListView.setStopScrolling(false);
		mListView.setVisibility(View.GONE);
		mLoadingProgress.setVisibility(View.VISIBLE);

		Snackbar.make(findViewById(R.id.layout_root), R.string.loading_questions, Snackbar.LENGTH_SHORT)
				.setAction(R.string.dismiss, mEmptySnackbarDismissListener)
				.show();

		try {
			Log.d(TAG, "Checking server for unanswered questions");
			HttpUrl url = HttpUrl.parse(TASKS_SERVER_URL);
			if (url == null) {
				Log.d(TAG, "Server unanswered questions url error: " + TASKS_SERVER_URL);
				reinstateListView(getString(R.string.question_download_error));
				return;
			}

			HttpUrl requestUrl = url.newBuilder()
					.addQueryParameter("type", "unanswered")
					.addQueryParameter("source", String.valueOf(mApiIdentifier))
					.addQueryParameter("k", SERVER_KEY)
					.build();
			Request request = new Request.Builder().url(requestUrl).build();
			mOkHttpClient.newCall(request).enqueue(new Callback() {
				@Override
				public void onFailure(@NonNull Call call, @NonNull IOException e) {
					Log.d(TAG, "Server unanswered questions request failed: " + call + ", " + e.getLocalizedMessage());
					reinstateListView(getString(R.string.question_download_error));
				}

				@Override
				public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
					ResponseBody responseBody = response.body();
					if (!response.isSuccessful() || responseBody == null) {
						Log.d(TAG, "Server unanswered questions response unsuccessful: " + responseBody);
						reinstateListView(getString(R.string.question_download_error));
					} else {
						final ArrayList<Question> questionsList = new ArrayList<>();
						String responseString = responseBody.string();

						try {
							JSONArray tasks = new JSONArray(responseString);
							for (int i = 0, n = tasks.length(); i < n; i += 1) {
								JSONObject task = tasks.getJSONObject(i);
								int questionId = task.getInt("id");
								String questionUrl = task.getString("url");
								questionsList.add(new Question(questionId, questionUrl));
							}
						} catch (JSONException e) {
							Log.d(TAG, "Server unanswered questions response parsing failed: " + responseString);
							reinstateListView(getString(R.string.question_download_error));
							return;
						}

						if (questionsList.size() > 0) {
							runOnUiThread(new Runnable() {
								@Override
								public void run() {
									Collections.sort(questionsList);
									mQuestions = questionsList;
									mQuestionAdapter = new QuestionAdapter(MainActivity.this, R.layout.answer_row,
											mQuestions);
									mListView.setAdapter(mQuestionAdapter);
									reinstateListView(null);
								}
							});
						} else {
							Log.d(TAG, "Server unanswered questions response found no questions that need answering");
							reinstateListView(getString(R.string.all_questions_answered));
							runOnUiThread(new Runnable() {
								@Override
								public void run() {
									if (mQuestionAdapter != null) {
										mQuestions.clear();
										mQuestionAdapter.updateList(mQuestions);
									}
								}
							});
						}
					}
				}
			});
		} catch (Exception e) {
			Log.d(TAG, "Server unanswered questions exception: " + e.getLocalizedMessage());
			reinstateListView(getString(R.string.question_download_error));
		}
	}

	private void reinstateListView(final String errorMessage) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				if (!TextUtils.isEmpty(errorMessage)) {
					Snackbar.make(findViewById(R.id.layout_root), errorMessage, Snackbar.LENGTH_LONG)
							.setAction(R.string.dismiss, mEmptySnackbarDismissListener)
							.show();
				}
				if (mProgressDialog != null) {
					mProgressDialog.dismiss();
					mProgressDialog = null;
				}
				mListView.setVisibility(View.VISIBLE);
				mLoadingProgress.setVisibility(View.GONE);
			}
		});
	}

	private void playAnswer(final String answerPath, QuestionAdapter.ViewHolder viewHolder) {
		Log.d(TAG, "Playing answer from " + answerPath);

		cancelAnswerPlayback(null); // any previous file
		viewHolder.mAddAnswerProgress.setVisibility(View.VISIBLE);
		mListView.setStopScrolling(true); // so hacky

		try {
			mAnswerMediaPlayer = new ViewSavingMediaPlayer(viewHolder);
			mAnswerMediaPlayer.setDataSource(answerPath);
			mAnswerMediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
				@Override
				public boolean onError(MediaPlayer mp, int what, int extra) {
					Log.d(TAG, "Answer playback failed for " + answerPath);
					cancelAnswerPlayback(getString(R.string.answer_playback_failed));
					return true; // so we don't call onCompletionListener;
				}
			});
			mAnswerMediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
				@Override
				public void onCompletion(MediaPlayer mp) {
					Log.d(TAG, "Answer playback completed for " + answerPath);
					cancelAnswerPlayback(null);
				}
			});
			mAnswerMediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
				@Override
				public void onPrepared(final MediaPlayer mediaPlayer) {
					Log.d(TAG, "Playing answer for " + answerPath);
					mediaPlayer.start();
				}
			});
			mAnswerMediaPlayer.prepareAsync();
		} catch (IOException e) {
			Log.d(TAG, "Answer streaming failed for " + answerPath + " (2)");
			cancelAnswerPlayback(getString(R.string.answer_playback_failed));
		}
	}

	private void cancelAnswerPlayback(final String errorMessage) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				if (!TextUtils.isEmpty(errorMessage)) {
					Snackbar.make(findViewById(R.id.layout_root), errorMessage, Snackbar.LENGTH_LONG)
							.setAction(R.string.dismiss, mEmptySnackbarDismissListener)
							.show();
				}

				if (mAnswerMediaPlayer != null) {
					if (mAnswerMediaPlayer.mViewHolder != null) {
						mAnswerMediaPlayer.mViewHolder.mAddAnswerProgress.setVisibility(View.GONE);
						mAnswerMediaPlayer.mViewHolder.mIsPlaybackActive = false;
						mAnswerMediaPlayer.mViewHolder = null;
					}
					mAnswerMediaPlayer.release();
				}
				mAnswerMediaPlayer = null;
				mListView.setStopScrolling(false);
			}
		});
	}

	private void playQuestion(final String questionPath, QuestionAdapter.ViewHolder viewHolder) {
		if (viewHolder.mIsRecordingActive || mEncoder != null || mAudioPoller != null) {
			Snackbar.make(findViewById(R.id.layout_root), R.string.complete_answer_warning_playback,
					Snackbar.LENGTH_SHORT)
					.setAction(R.string.dismiss, mEmptySnackbarDismissListener)
					.show();
			return;
		}

		Log.d(TAG, "Playing question from " + questionPath);

		viewHolder.mPlayButton.setImageResource(R.drawable.ic_stop_indigo_500_36dp);
		viewHolder.mPlayProgress.setVisibility(View.VISIBLE);
		viewHolder.mIsPlaybackActive = true;

		cleanupPreviousMediaPlayer(); // any previous file
		mListView.setStopScrolling(true); // so hacky

		try {
			mQuestionMediaPlayer = new ViewSavingMediaPlayer(viewHolder);
			mQuestionMediaPlayer.setDataSource(questionPath);
			mQuestionMediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
				@Override
				public boolean onError(MediaPlayer mp, int what, int extra) {
					Log.d(TAG, "Question streaming failed for " + questionPath);
					cancelQuestionPlayback(getString(R.string.question_playback_failed));
					return true; // so we don't call onCompletionListener;
				}
			});
			mQuestionMediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
				@Override
				public void onCompletion(MediaPlayer mp) {
					Log.d(TAG, "Question streaming completed for " + questionPath);
					cancelQuestionPlayback(null);
				}
			});
			mQuestionMediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
				@Override
				public void onPrepared(final MediaPlayer mediaPlayer) {
					Log.d(TAG, "Streaming question for " + questionPath);
					mediaPlayer.start();
				}
			});
			mQuestionMediaPlayer.prepareAsync(); // async as this can be a remote file
		} catch (IOException e) {
			Log.d(TAG, "Question streaming failed for " + questionPath + " (2)");
			cancelQuestionPlayback(getString(R.string.question_playback_failed));
		}
	}

	private void cancelQuestionPlayback(final String errorMessage) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				if (!TextUtils.isEmpty(errorMessage)) {
					Snackbar.make(findViewById(R.id.layout_root), errorMessage, Snackbar.LENGTH_LONG)
							.setAction(R.string.dismiss, mEmptySnackbarDismissListener)
							.show();
				}
				cleanupPreviousMediaPlayer();
			}
		});
	}

	private void cleanupPreviousMediaPlayer() {
		if (mQuestionMediaPlayer != null) {
			Log.d(TAG, "Ending question playback");
			if (mQuestionMediaPlayer.mViewHolder != null) {
				mQuestionMediaPlayer.mViewHolder.mPlayButton.setImageResource(R.drawable.ic_play_arrow_indigo_500_36dp);
				mQuestionMediaPlayer.mViewHolder.mPlayProgress.setVisibility(View.GONE);
				mQuestionMediaPlayer.mViewHolder.mIsPlaybackActive = false;
				mQuestionMediaPlayer.mViewHolder = null;
			}
			mQuestionMediaPlayer.release();
			mListView.setStopScrolling(false);
		}
		mQuestionMediaPlayer = null;
	}

	private void startRecording(final Question question, final QuestionAdapter.ViewHolder viewHolder) {
		if (viewHolder.mIsRecordingActive || mEncoder != null || mAudioPoller != null) {
			Snackbar.make(findViewById(R.id.layout_root), R.string.complete_answer_warning_recording,
					Snackbar.LENGTH_SHORT)
					.setAction(R.string.dismiss, mEmptySnackbarDismissListener)
					.show();
			return;
		}

		mProgressDialog = new ProgressDialog(MainActivity.this);
		mProgressDialog.setMessage(getString(R.string.reserving_question));
		mProgressDialog.setCancelable(false);
		mProgressDialog.show();

		// first reserve the question
		try {
			Log.d(TAG, "Reserving question from server");
			HttpUrl url = HttpUrl.parse(RESERVE_SERVER_URL);
			if (url == null) {
				Log.d(TAG, "Server reserve questions url error: " + RESERVE_SERVER_URL);
				reinstateListView(getString(R.string.reserving_failed));
				return;
			}

			HttpUrl requestUrl = url.newBuilder()
					.addQueryParameter("id", String.valueOf(question.mId))
					.addQueryParameter("source", String.valueOf(mApiIdentifier))
					.addQueryParameter("k", SERVER_KEY)
					.build();
			Request request = new Request.Builder().url(requestUrl).build();
			mOkHttpClient.newCall(request).enqueue(new Callback() {
				@Override
				public void onFailure(@NonNull Call call, @NonNull IOException e) {
					Log.d(TAG, "Server reserve question request failed: " + call + ", " + e.getLocalizedMessage());
					reinstateListView(getString(R.string.reserving_failed));
				}

				@Override
				public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
					ResponseBody responseBody = response.body();
					if (!response.isSuccessful() || responseBody == null) {
						Log.d(TAG, "Server reserve question response unsuccessful: " + responseBody);
						reinstateListView(getString(R.string.reserving_failed));
					} else {
						String responseString = responseBody.string();
						if ("success".equals(responseString)) {
							runOnUiThread(new Runnable() {
								@Override
								public void run() {
									if (mProgressDialog != null) {
										mProgressDialog.dismiss();
										mProgressDialog = null;
									}

									Log.d(TAG, "Starting voice recorder to answer question " + question.mId);

									viewHolder.mAddAnswerButton.setText(R.string.stop);
									viewHolder.mAddAnswerProgress.setVisibility(View.VISIBLE);
									viewHolder.mIsPlaybackActive = false;
									viewHolder.mIsRecordingActive = true;

									cleanupPreviousMediaPlayer(); // don't record while playing
									mListView.setStopScrolling(true); // so hacky

									mEncoder = new AudioEncoder(mOutputDirectory, question, viewHolder);
									mEncoder.prepare();
									mAudioPoller = new AudioSoftwarePoller();
									mAudioPoller.setAudioEncoder(mEncoder);
									mEncoder.setAudioSoftwarePoller(mAudioPoller);
									mAudioPoller.startPolling();
								}
							});
						} else {
							// reserved by someone else since we loaded - remove from our list
							runOnUiThread(new Runnable() {
								@Override
								public void run() {
									mQuestions.remove(question);
									mQuestionAdapter = new QuestionAdapter(MainActivity.this, R.layout.answer_row,
											mQuestions);
									mListView.setAdapter(mQuestionAdapter);
									reinstateListView(getString(R.string.reserving_conflict));
								}
							});
						}
					}
				}
			});
		} catch (Exception e) {
			Log.d(TAG, "Server reserve question exception: " + e.getLocalizedMessage());
			reinstateListView(getString(R.string.reserving_failed));
		}
	}

	private void stopRecording(final QuestionAdapter.ViewHolder viewHolder) {
		if (mEncoder != null) {
			if (viewHolder != null) {
				if (getString(R.string.saving).contentEquals(viewHolder.mAddAnswerButton.getText())) {
					return; // already saving - no action needed
				}
				viewHolder.mAddAnswerButton.setText(R.string.saving);
			}
			mEncoder.setEncodingCompletedCallback(new AudioEncoder.EncoderCallback() {
				@Override
				public void onEncodingCompleted(final File outputFile) {
					long recordingTime = getRecordingTime(outputFile);
					Log.d(TAG, "Encoding completed; previewing answer");

					// rename with question id
					Question question = mEncoder.getQuestion();
					File newAnswerFile = new File(mOutputDirectory, String.format(Locale.US, ANSWER_FILE_FORMAT,
							question.mId, recordingTime, AUDIO_FILE_EXTENSION));
					if (outputFile.renameTo(newAnswerFile)) {
						question.mAnswerFile = newAnswerFile;
						endAnswer(null);
					} else {
						Log.d(TAG, "File rename failed: " + newAnswerFile.getName());
						endAnswer(getString(R.string.save_answer_failed));
					}
				}
			});
			mAudioPoller.stopPolling();
			File outputFile = mEncoder.stop();
			Log.d(TAG, "Recorded audio to " + outputFile.getAbsolutePath() + " - waiting for encoding");
		}
	}

	private void uploadFile(File answerFile, final Question question) {
		Log.d(TAG, "Uploading answer " + answerFile);

		try {
			RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
					.addFormDataPart("answer", answerFile.getName(), RequestBody.create(MediaType.parse("audio/m4a"),
							answerFile))
					.addFormDataPart("id", String.valueOf(question.mId))
					.addFormDataPart("source", String.valueOf(mApiIdentifier))
					.addFormDataPart("k", SERVER_KEY)
					.build();
			Request request = new Request.Builder().url(FILE_SERVER_URL).post(requestBody).build();
			mOkHttpClient.newCall(request).enqueue(new Callback() {
				@Override
				public void onFailure(@NonNull Call call, @NonNull IOException e) {
					Log.d(TAG, "Upload request failed: " + call + ", " + e.getLocalizedMessage());
					endAnswer(getString(R.string.save_answer_failed));
				}

				@Override
				public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
					ResponseBody responseBody = response.body();
					if (!response.isSuccessful() || responseBody == null) {
						Log.d(TAG, "Upload response unsuccessful: " + responseBody);
						endAnswer(getString(R.string.save_answer_failed));
					} else {
						String responseString = responseBody.string();
						if ("error".equals(responseString)) {
							Log.d(TAG, "Upload response unsuccessful: " + responseString);
							endAnswer(getString(R.string.save_answer_failed));
						}
						String[] responseParts = responseString.split(","); // 0 = question id; 1 = url
						int questionNumber = 0;
						try {
							questionNumber = Integer.parseInt(responseParts[0]);
						} catch (NumberFormatException ignored) {
						}
						if (questionNumber > 0) {
							Log.d(TAG, "Upload of answer to question " + questionNumber + " successful: " +
									responseParts[1]);
							runOnUiThread(new Runnable() {
								@Override
								public void run() {
									mAudioPoller = null;
									mEncoder = null;
									mQuestions.remove(question);
									mQuestionAdapter.updateList(mQuestions);
									endAnswer(getString(R.string.save_answer_succeeded, question.mId));
								}
							});

						} else {
							Log.d(TAG, "Upload response parsing failed: " + responseString);
							endAnswer(getString(R.string.save_answer_failed));
						}
					}
				}
			});
		} catch (Exception e) {
			Log.d(TAG, "Upload failed: " + e.getLocalizedMessage());
			endAnswer(getString(R.string.save_answer_failed));
		}
	}

	private void endAnswer(final String errorMessage) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				if (!TextUtils.isEmpty(errorMessage)) {
					Snackbar.make(findViewById(R.id.layout_root), errorMessage, Snackbar.LENGTH_LONG)
							.setAction(R.string.dismiss, mEmptySnackbarDismissListener)
							.show();
				}
				if (mEncoder != null) {
					Log.d(TAG, "Ending answer");
					QuestionAdapter.ViewHolder viewHolder = mEncoder.getViewHolder();
					if (viewHolder != null) {
						viewHolder.mAddAnswerButton.setText(R.string.play_answer);
						viewHolder.mAddAnswerButton.setCompoundDrawablesWithIntrinsicBounds(0, 0,
								R.drawable.ic_play_arrow_indigo_500_36dp, 0);
						viewHolder.mAddAnswerProgress.setVisibility(View.GONE);
						viewHolder.mSubmitView.setVisibility(View.VISIBLE);
						viewHolder.mCancelAnswerButton.setVisibility(View.VISIBLE);
						viewHolder.mSubmitAnswerButton.setVisibility(View.VISIBLE);
						viewHolder.mSubmitAnswerProgress.setVisibility(View.GONE);
						viewHolder.mIsRecordingActive = false;
					}
					mListView.setStopScrolling(false);
				}
			}
		});
	}

	// parses *only* the original audio files from the encoder, not the ones we have already renamed
	private long getRecordingTime(File outputFile) {
		long recordingTime;
		try {
			recordingTime = Long.parseLong(outputFile.getName().replace(AUDIO_FILE_EXTENSION, ""));
		} catch (NumberFormatException e) {
			recordingTime = System.currentTimeMillis(); // as a backup
		}
		return recordingTime;
	}

	private QuestionAdapter.ViewHolder getViewHolder(View view) {
		return (QuestionAdapter.ViewHolder) ((RelativeLayout) view.getParent().getParent()).getTag();
	}

	class QuestionAdapter extends ArrayAdapter<Question> {
		private List<Question> mItems;
		private LayoutInflater mInflater;

		QuestionAdapter(Context context, int resourceId, ArrayList<Question> items) {
			super(context, resourceId, items);
			mItems = items;
			mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
		}

		@NonNull
		@Override
		public View getView(int position, View convertView, @NonNull ViewGroup parent) {
			View view = convertView;
			ViewHolder holder;
			if (view == null) {
				view = mInflater.inflate(R.layout.answer_row, parent, false);
				holder = new ViewHolder();
				holder.mQuestion = view.findViewById(R.id.question_id);
				holder.mQuestion.setOnClickListener(mButtonClickListener);
				holder.mPlayButton = view.findViewById(R.id.play_button);
				holder.mPlayButton.setOnClickListener(mButtonClickListener);
				holder.mPlayProgress = view.findViewById(R.id.play_progress);
				holder.mPlayProgress.setOnClickListener(mButtonClickListener);
				holder.mAddAnswerButton = view.findViewById(R.id.add_answer_button);
				holder.mAddAnswerButton.setOnClickListener(mButtonClickListener);
				holder.mAddAnswerProgress = view.findViewById(R.id.add_answer_progress);
				holder.mAddAnswerProgress.setOnClickListener(mButtonClickListener);

				holder.mSubmitView = view.findViewById(R.id.submit_answer_layout);
				holder.mCancelAnswerButton = view.findViewById(R.id.cancel_answer_button);
				holder.mCancelAnswerButton.setOnClickListener(mButtonClickListener);
				holder.mSubmitAnswerButton = view.findViewById(R.id.submit_answer_button);
				holder.mSubmitAnswerButton.setOnClickListener(mButtonClickListener);
				holder.mSubmitAnswerProgress = view.findViewById(R.id.submit_answer_progress);
				view.setTag(holder);
			} else {
				holder = (ViewHolder) view.getTag();
			}

			Question item = getItem(position);
			if (item != null) {
				holder.mQuestion.setText(String.valueOf(item.mId));
				holder.mPlayButton.setImageResource(R.drawable.ic_play_arrow_indigo_500_36dp);
				holder.mPlayProgress.setVisibility(View.GONE);

				holder.mAddAnswerProgress.setVisibility(View.GONE);
				holder.mSubmitAnswerProgress.setVisibility(View.GONE);
				holder.mAddAnswerButton.setText(R.string.answer);
				holder.mAddAnswerButton.setCompoundDrawablesWithIntrinsicBounds(0, 0,
						R.drawable.ic_fiber_manual_record_red_500_36dp, 0);
				holder.mSubmitView.setVisibility(View.GONE);

				holder.mIsPlaybackActive = false;
				holder.mIsRecordingActive = false;
			}

			return view;
		}

		@Override
		public int getCount() {
			return mItems.size();
		}

		@Override
		public Question getItem(int position) {
			return mItems.get(position);
		}

		void updateList(ArrayList<Question> items) {
			mItems = items;
			notifyDataSetChanged();
		}

		class ViewHolder {
			TextView mQuestion;
			ImageButton mPlayButton;
			ProgressBar mPlayProgress;
			Button mAddAnswerButton;
			ProgressBar mAddAnswerProgress;

			RelativeLayout mSubmitView;
			Button mCancelAnswerButton;
			Button mSubmitAnswerButton;
			ProgressBar mSubmitAnswerProgress;

			boolean mIsPlaybackActive;
			boolean mIsRecordingActive;
		}
	}

	private boolean checkHasPermissions() {
		int audioPermission = ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO);
		int storagePermission = ActivityCompat.checkSelfPermission(MainActivity.this,
				Manifest.permission.WRITE_EXTERNAL_STORAGE);
		if (audioPermission != PackageManager.PERMISSION_GRANTED ||
				storagePermission != PackageManager.PERMISSION_GRANTED) {
			if (ActivityCompat.shouldShowRequestPermissionRationale(MainActivity.this,
					Manifest.permission.RECORD_AUDIO)) {
				showPermissionMessageDialog(R.string.audio_permission_message);
			} else if (ActivityCompat.shouldShowRequestPermissionRationale(MainActivity.this,
					Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
				showPermissionMessageDialog(R.string.external_storage_permission_message);
			}
			ActivityCompat.requestPermissions(MainActivity.this, new String[]{
					Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE
			}, REQUEST_PERMISSIONS);
			return false;
		} else {
			return true;
		}
	}

	private void showPermissionMessageDialog(int message) {
		Snackbar.make(findViewById(R.id.layout_root), message, Snackbar.LENGTH_INDEFINITE)
				.setAction(R.string.retry, new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						checkHasPermissions();
					}
				})
				.show();
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
										   @NonNull int[] grantResults) {
		switch (requestCode) {
			case REQUEST_PERMISSIONS:
				int audioPermission = ActivityCompat.checkSelfPermission(MainActivity.this,
						Manifest.permission.RECORD_AUDIO);
				int storagePermission = ActivityCompat.checkSelfPermission(MainActivity.this,
						Manifest.permission.WRITE_EXTERNAL_STORAGE);
				if (audioPermission == PackageManager.PERMISSION_GRANTED &&
						storagePermission == PackageManager.PERMISSION_GRANTED) {
					initialise();
				} else if (audioPermission != PackageManager.PERMISSION_GRANTED) {
					showPermissionMessageDialog(R.string.audio_permission_message);
				} else {
					showPermissionMessageDialog(R.string.external_storage_permission_message);
				}
				break;

			default:
				super.onRequestPermissionsResult(requestCode, permissions, grantResults);
				break;
		}
	}

	private boolean checkHasLogin() {
		SharedPreferences preferences = getPreferences(Context.MODE_PRIVATE);
		if (getString(R.string.app_password).equals(preferences.getString("login", null))) {
			mApiIdentifier = preferences.getInt("source", -1);
			if (mApiIdentifier >= 0) {
				findViewById(R.id.login_frame).setVisibility(View.GONE);
				return true;
			}
		}
		return false;
	}
}
