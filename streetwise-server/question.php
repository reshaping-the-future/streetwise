<?php
// this API receives an audio recording of a question, then sends it for answering
require('_include.php');

header('Content-Type: text/plain; charset=utf-8'); // return plain text for JS parsing of result
try {
	if (!isset($_POST['k'])) {
		throw new RuntimeException('Incorrect key 1 (question submission)');
	}
	if (strcmp($SPEECH_APPLIANCE_KEY, $_POST['k']) !== 0) {
		throw new RuntimeException('Incorrect key 2 (question submission)');
	}

	// get previously used question numbers
	$usedNumbers = array(0, 1234); // cannot have 0000 or 1234 as an ID (1234 is the helper question; 0 is search start point, below)
	$questionFiles = preg_grep('~\.(mp3)$~', scandir($BASE_QUESTION_DIRECTORY));
	foreach($questionFiles as $file) {
		$fileParts = explode('-', $file);
		$usedNumbers[] = intval($fileParts[0]);
	}

	// generate a new question number that hasn't yet been used, but make sure it is 4 digits
	$startingNumber = 1000;
	$length = pow(10, log10($startingNumber) + 1);
	$currentQuestionNumber = 0;
	while(in_array($currentQuestionNumber, $usedNumbers, TRUE) === TRUE) {
		$currentQuestionNumber = mt_rand(0, $length - ($startingNumber + 1)) + $startingNumber;
	}

	logMessage('Started upload for question ' . $currentQuestionNumber . ' (question submission)');

	// undefined | multiple files | $_FILES corruption attack: invalid
	if (!isset($_FILES['question']['error']) ||
		is_array($_FILES['question']['error'])) {
		throw new RuntimeException('Unknown file error (question submission)');
	}

	// $_FILES['question']['error'] values mean various failures
	switch ($_FILES['question']['error']) {
		case UPLOAD_ERR_OK:
			break;
		case UPLOAD_ERR_NO_FILE:
			throw new RuntimeException('No file sent (question submission)'); // no file sent
		case UPLOAD_ERR_INI_SIZE:
		case UPLOAD_ERR_FORM_SIZE:
			throw new RuntimeException('Invalid file size (question submission)'); // file too big
		default:
			throw new RuntimeException('Unknown error (question submission)');
	}

	// check filesize
	if ($_FILES['question']['size'] > 1500000) {
		throw new RuntimeException('File size error (question submission)'); // file too big - 1500000 bytes is about 1.5 mins in mp3
	}

	// can't trust the given name, so just hash the file itself for a safe version
	// we keep the id in the filename as well for answer parsing
	$fileName = sprintf('%s%d-%s.mp3',
		$BASE_QUESTION_DIRECTORY,
		$currentQuestionNumber,
		sha1_file($_FILES['question']['tmp_name'])
	);

	// archive existing file if present (e.g., question number reuse)
	$updateTime = round(microtime(true) * 1000);
	if(file_exists($fileName)) {
		rename($fileName, $fileName . '.' . $updateTime . '.old');
	}

	if (!move_uploaded_file(
		$_FILES['question']['tmp_name'],
		$fileName
	)) {
		throw new RuntimeException('Unknown save error (question submission)'); // failed to move file
	}

	$voiceFileUrl = $ROOT_URL . $fileName;
	logMessage('Upload completed for question; now sending request to crowd: ' . $currentQuestionNumber . ', ' . $voiceFileUrl);
	echo $currentQuestionNumber . ',' . $voiceFileUrl;

	// post notification to devices
	if (!empty($FCM_KEY)) {
		$fcmUrl = 'https://fcm.googleapis.com/fcm/send';
		$fcmHeader = array('Authorization: key=' . $FCM_KEY, 'Content-Type: application/json');
		$fcmData = '{"to":"/topics/' . $FCM_TOPIC . '", "notification":{"title":"New question available", "body":"Tap to answer question ' . $currentQuestionNumber . '"}, "priority":"high", "time_to_live":600}';

		$curlConnection = curl_init();
		curl_setopt($curlConnection, CURLOPT_URL, $fcmUrl);
		curl_setopt($curlConnection, CURLOPT_HTTPHEADER, $fcmHeader);
		curl_setopt($curlConnection, CURLOPT_POST, TRUE);
		curl_setopt($curlConnection, CURLOPT_POSTFIELDS, $fcmData);
		curl_setopt($curlConnection, CURLOPT_FAILONERROR, TRUE);
		curl_setopt($curlConnection, CURLOPT_RETURNTRANSFER, TRUE);
		$fcmResult = curl_exec($curlConnection);
		if(curl_errno($curlConnection) || $fcmResult === FALSE) {
			// something went wrong, but as it's only a notification, ignore
		}
		curl_close($curlConnection);
	}

} catch (RuntimeException $e) {
	logMessage('Question upload error: ' . $e->getMessage());
	echo 'error';
}
?>
