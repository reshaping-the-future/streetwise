package ac.robinson.streetwise;

import android.content.Intent;
import android.support.v4.content.LocalBroadcastManager;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class StreetWiseFirebaseMessagingService extends FirebaseMessagingService {
	public static final String MESSAGE_RECEIVED = "message_received";

	@Override
	public void onMessageReceived(RemoteMessage remoteMessage) {
		if (remoteMessage.getNotification() != null) {
			// Log.d("StreetWiseService",
			// 		"Message received in service: " + remoteMessage.getNotification().getBody() + ", from: " +
			// 				remoteMessage.getFrom());
			Intent intent = new Intent(MESSAGE_RECEIVED);
			LocalBroadcastManager.getInstance(StreetWiseFirebaseMessagingService.this).sendBroadcast(intent);
		}
	}
}
