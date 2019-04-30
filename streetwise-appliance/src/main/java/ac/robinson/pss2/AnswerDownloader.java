package ac.robinson.pss2;

import okhttp3.*;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

class AnswerDownloader {
	private static final int CHECK_INTERVAL = 120; // seconds (default: 2 minutes)

	private static final int DOWNLOAD_BUFFER_SIZE = 8 * 1024;

	private ScheduledFuture mScheduledFuture;
	private File mOutputDirectory;
	private int mPss2Id;

	interface DownloadCompletedCallback {
		void onDownloadComplete(String outputLocation);

		void onDownloadFailed();
	}

	AnswerDownloader(File storageDirectory, int pss2Id) {
		mOutputDirectory = storageDirectory;
		mPss2Id = pss2Id;
		ScheduledExecutorService mScheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
		Runnable mInternetCheckRunnable = () -> {
			try {
				downloadLatestAnswers();
			} catch (Exception e) {
				Pss2.logEvent("Background server answer downloader failed: " + e.getLocalizedMessage());
			}
		};
		mScheduledFuture = mScheduledExecutorService.scheduleAtFixedRate(mInternetCheckRunnable, 0, CHECK_INTERVAL,
				TimeUnit.SECONDS);
	}

	private void downloadLatestAnswers() {
		Pss2.logEvent("Background checking server for recently answered questions");
		HttpUrl url = HttpUrl.parse(Pss2.ANSWER_SERVER_URL);
		if (url == null) {
			Pss2.logEvent("Background server answer url error: " + Pss2.ANSWER_SERVER_URL);
			return;
		}

		HttpUrl requestUrl = url.newBuilder()
				.addQueryParameter("source", String.valueOf(mPss2Id))
				.addQueryParameter("k", Pss2.SERVER_KEY)
				.addQueryParameter("type", "answered")
				.build();
		Request request = new Request.Builder().url(requestUrl).build();
		Pss2.getOkHttpClient().newCall(request).enqueue(new Callback() {
			@Override
			public void onFailure(Call call, IOException e) {
				Pss2.logEvent("Background server answer request failed: " + call + ", " + e.getLocalizedMessage());
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException {
				ResponseBody responseBody = response.body();
				if (!response.isSuccessful() || responseBody == null) {
					Pss2.logEvent("Background server answer response unsuccessful: " +
							(responseBody != null ? responseBody.string() : null));
				} else {
					HashMap<Integer, String> answerMap = new HashMap<>();
					String responseString;
					try {
						responseString = responseBody.string();
						if ("error".equals(responseString)) {
							throw new IOException("Invalid response string (error)");
						}
					} catch (IOException e) {
						Pss2.logEvent("Background server answer response failed: response body string error");
						return;
					}

					try {
						JSONArray tasks = new JSONArray(responseString);
						Pss2.logEvent(
								"Background server answer response success - parsing " + tasks.length() + " tasks");
						for (int i = 0, n = tasks.length(); i < n; i += 1) {
							JSONObject task = tasks.getJSONObject(i);
							int questionId = task.getInt("id");
							String answerUrl = task.getString("url");
							answerMap.put(questionId, answerUrl);
						}
					} catch (JSONException e) {
						Pss2.logEvent("Background server answer response parsing failed");
						// Pss2.logEvent("Background server answer response parsing failed: " + responseString);
					}

					if (answerMap.size() > 0) {
						checkAndDownloadAnswers(answerMap);
					} else {
						Pss2.logEvent("Background server answer response found no answers to download");
					}
				}
			}
		});
	}

	private void checkAndDownloadAnswers(HashMap<Integer, String> answerMap) {
		Pss2.logEvent("Checking and downloading where necessary " + answerMap.size() + " answers");

		// as in the answer app, most of this design complexity is so we can move or reinstall the application without
		// losing all our storage (i.e., rather than a database) that is linked to a signing key

		File[] fileList = mOutputDirectory.listFiles();
		StringBuilder ignoredAnswers = new StringBuilder();
		if (fileList != null) {
			// first remove any answers we've already downloaded
			for (File file : fileList) {
				String fileName = file.getName();
				if (file.isFile()) {
					Matcher answerMatcher = Pss2.ANSWER_FILE_PATTERN.matcher(fileName);
					if (answerMatcher.matches()) {
						try {
							int questionId = Integer.parseInt(answerMatcher.group(1));
							if (answerMap.get(questionId) != null) {
								// Pss2.logEvent("Found local answer: task " + questionId + ", " + fileName +
								//		" - not downloading");
								ignoredAnswers.append(questionId).append(", ");
								answerMap.remove(questionId);
							}
						} catch (Exception ignored) {
						}
					}
				}

				if (answerMap.size() <= 0) {
					// Pss2.logEvent("Server answer processing complete: all answers already downloaded");
					break;
				}

			}

			// now download any answers that remain, as long as we can find a matching question
			if (answerMap.size() > 0) {
				for (File file : fileList) {
					if (file.isFile()) {
						String fileName = file.getName();
						Matcher questionMatcher = Pss2.QUESTION_FILE_PATTERN.matcher(fileName);
						if (questionMatcher.matches()) {
							try {
								int questionId = Integer.parseInt(questionMatcher.group(1));
								String answerFile = answerMap.get(questionId);
								if (answerFile != null) {
									Pss2.logEvent(
											"No local version of server answer found: task " + questionId + "," + " " +
													fileName + " - starting download");
									downloadAnswer(questionId, answerFile, mOutputDirectory, null);
									answerMap.remove(questionId);
								}
							} catch (Exception ignored) {
							}
						}
					}
				}
			}
		}

		Pss2.logEvent("Answer downloading complete (previously downloaded: " + ignoredAnswers.toString() +
				"; remainder not present locally)");
	}

	static void downloadAnswer(int localId, final String answerFile, File outputDirectory,
							   DownloadCompletedCallback callback) {
		String fileExtension = answerFile.trim().substring(answerFile.lastIndexOf("."));
		Date answerDate = new Date();
		final File tempFile = new File(outputDirectory, new SimpleDateFormat(
				"'" + answerDate.getTime() + "'_yyyy-MM-dd_HH-mm-ss'" + fileExtension + "'").format(answerDate));
		final File outputFile = new File(outputDirectory, String.format(Pss2.ANSWER_FILE_FORMAT, localId,
				tempFile.getName()));
		Pss2.logEvent(
				"Downloading " + answerFile + " with local id " + localId + " to " + outputFile.getName() + " (temp:" +
						" " + tempFile.getName() + ")");

		try {
			if (!outputFile.createNewFile()) {
				Pss2.logEvent("Error initiating download: unable to create file");
				if (callback != null) {
					callback.onDownloadFailed();
				}
				return;
			}
		} catch (IOException e) {
			Pss2.logEvent("Error initiating download: " + e.getLocalizedMessage());
			if (callback != null) {
				callback.onDownloadFailed();
			}
			return;
		}

		try {
			OkHttpClient okHttpClient = Pss2.getOkHttpClient();
			Request request = new Request.Builder().url(answerFile).build();
			okHttpClient.newCall(request).enqueue(new Callback() {
				@Override
				public void onFailure(Call call, IOException e) {
					Pss2.logEvent("Failed to download " + answerFile + ": " + call + ", " + e.getLocalizedMessage() +
							" (cache deleted: " + tempFile.delete() + ")");
					if (callback != null) {
						callback.onDownloadFailed();
					}
				}

				@Override
				public void onResponse(Call call, Response response) throws IOException {
					ResponseBody responseBody = response.body();
					if (!response.isSuccessful() || responseBody == null) {
						Pss2.logEvent("Response error when downloading: " +
								(responseBody != null ? responseBody.string() : null) + " (cache deleted: " +
								tempFile.delete() + ")");
						if (callback != null) {
							callback.onDownloadFailed();
						}
					} else {
						BufferedSource source = responseBody.source();
						BufferedSink sink = Okio.buffer(Okio.sink(tempFile));
						Buffer sinkBuffer = sink.buffer();

						long contentLength = responseBody.contentLength();
						long totalBytesRead = 0;
						for (long bytesRead; (bytesRead = source.read(sinkBuffer, DOWNLOAD_BUFFER_SIZE)) != -1; ) {
							sink.emit();
							totalBytesRead += bytesRead;
							// int progress = (int) ((totalBytesRead * 100) / contentLength);
							// Log.v("Download progress for " + answerFile + ": " + progress);
						}
						sink.flush();
						sink.close();
						source.close();

						if (totalBytesRead > 0 && totalBytesRead == contentLength && tempFile.length() > 0 &&
								tempFile.renameTo(outputFile)) {
							Pss2.logEvent(
									"Download completed for " + answerFile + " to " + outputFile.getName() + " (" +
											totalBytesRead + " bytes)");
							if (callback != null) {
								callback.onDownloadComplete(outputFile.getAbsolutePath());
							}
						} else {
							Pss2.logEvent(
									"Download error checking " + tempFile.getName() + " / " + outputFile.getName() +
											" (cache deleted: " + tempFile.delete() + ", " + outputFile.delete() +
											"; bytes read: " + totalBytesRead + " of " + contentLength + ")");
							if (callback != null) {
								callback.onDownloadFailed();
							}
						}
					}
				}
			});
		} catch (Exception e) {
			Pss2.logEvent("Download failed for " + answerFile + " (cache deleted: " + tempFile.delete() + ", " +
					outputFile.delete() + "): " + e.getLocalizedMessage());
			if (callback != null) {
				callback.onDownloadFailed();
			}
		}
	}

	void stop() {
		if (mScheduledFuture != null) {
			mScheduledFuture.cancel(true);
		}
	}
}
