# StreetWise server
The server side of StreetWise is implemented in PHP, and uses a simple file-based method for storage and retrieval of questions and answers.

## Setup
To install the StreetWise server-side code, upload the contents of this directory to your PHP server, making sure the directories `q` and `a` are writeable by your web server.

To complete setup, change the following values in `_globals.php`:
* `$ROOT_URL`: Set to the full public URL of the server and directory on which you have hosted the server, including a trailing slash (e.g., `https://server.test/streetwise/`).
* `$ANSWER_APP_KEY`: Set to a secret key value that is shared between the server and the [answer app](../streetwise-app).
* `$SPEECH_APPLIANCE_KEY`: Set to a secret key value that is shared between the server and the [speech appliance](../streetwise-appliance).

This is the miniumum configuration required to run the StreetWise system, but you are welcome to edit the rest of the configuration file to set additional options (e.g., enable notifications and/or logging; configure timeouts, etc).

## License
Apache 2.0
