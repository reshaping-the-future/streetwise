package ac.robinson.streetwise;

import android.app.Application;

import com.google.firebase.messaging.FirebaseMessaging;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

public class StreetWiseAnswersApplication extends Application {

	private static OkHttpClient sOkHttpClient;

	@Override
	public void onCreate() {
		super.onCreate();
		try {
			FirebaseMessaging.getInstance().subscribeToTopic("streetwise");
		} catch (Throwable ignored) {
			// we don't care if messaging doesn't work
		}
	}

	public static OkHttpClient getOkHttpClient() {
		if (sOkHttpClient == null) {
			sOkHttpClient = new OkHttpClient.Builder().connectTimeout(5000, TimeUnit.MILLISECONDS)
					.readTimeout(5000, TimeUnit.MILLISECONDS)
					.build();
		}
		return sOkHttpClient;
	}
}
