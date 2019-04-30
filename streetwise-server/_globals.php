<?php

// -------------- Setup variables --------------

// The URL (including path where necessary) of the server where this code is running.
// This should include the trailing slash.
$ROOT_URL = '*** YOUR SERVER URL HERE ***';

// API keys, for authentication of interaction between applicances, the answer app and this server.
// You should generate your own value for these, and then insert the same key in the relevant variables of the speech appliance and answer app code.
$ANSWER_APP_KEY = '*** YOUR ANSWER APP API KEY HERE ***'; // answer app
$SPEECH_APPLIANCE_KEY = '*** YOUR SPEECH APPLIANCE API KEY HERE ***'; // speech appliances

// A Firebase Cloud Messaging key that will be used to send notifications about new questions.
// To disable notifications, leave this value blank.
$FCM_KEY = '';

// If using Firebase notifications, the topic name to use (default: streetwise).
// It can be useful to change this between different deployments, but make sure that the answer app uses the same value (see StreetWiseAnswersApplication.java)
$FCM_TOPIC = 'streetwise';

// The directory to use for the log file (streetwise.log). The directory should be writeable, and not publicly-accessible.
// To disable logging, leave this value blank.
$LOG_FILE_PATH = '';

// Where to store uploaded question and answer files.
// Both should be given relative to the current working directory (e.g., 'a/'), and both directories should be writeable.
$BASE_ANSWER_DIRECTORY = 'a/';
$BASE_QUESTION_DIRECTORY = 'q/';


// -------------- Configuration variables --------------

// How long (in seconds) to allow a user to reserve a question for.
// For example, the default value of 240 gives someone reserving a question using the answer app four minutes of exclusivity to answer a question.
$QUESTION_RESERVATION_TIME = 240;

// To speed up answer retrieval, the speech appliance checks periodically to see whether any new answers have been posted, and pre-emptively downloads these in the background. 
// The default value of 600 (seconds) means that all answers from the past 10 minutes will be included in the response to this check. Normally, this value would not need to be changed.
$RECENT_ANSWERS_LIST_TIME = 600;

?>