# StreetWise answer app
The StreetWise answer app allows you to remotely view and answer questions.

## Setup
The only essential configuration step for the StreetWise answer app is to configure the server URL and API key in order to allow the app to discover available questions. Edit `server_url` and `server_key` in [configuration.xml](app/src/main/res/values/configuration.xml) to set this up.

Once you have configured the URL, launching the app will prompt you for login details. The number that is requested is for tracking purposes only (and only when logging is enabled in `_globals.php`), which allows you to link answers with a particular device. Any positive integer is permitted, though we recommend you use different numbers to those used for the StreetWise appliance in order to help simplify tracking.

The default password is `streetwise`. We recommend that you only distribute this app internally, or through Google Play's private channels. If you decide to release your app publicly then its password function will provide a small amount of protection. See `app_password` in [configuration.xml](app/src/main/res/values/configuration.xml) to change this value. Please note, however, that it is very easy to extract static passwords or keys from publicly-distributed apps. You should not rely on this password as a strong access control mechanism.

It can be helpful to receive notifications whenever new questions are asked on a StreetWise appliance. In order to enable notifications, you will need to [register for Firebase Cloud Messaging](https://firebase.google.com/docs/android/setup). Once you have entered your app's details, place the `google-services.json` configuration file in the [app](app) directory, and add the key you were given into `_globals.php` on your server. Redeploying the app should automatically add the notification capability.

Finally, for public releases, Google Play will not allow you to upload the app without first changing its package name. To do this, edit the `applicationId` field in the app module's [build.gradle](app/build.gradle).

## License
Apache 2.0
