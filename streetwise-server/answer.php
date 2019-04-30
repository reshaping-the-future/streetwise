<?php
// this API receives an audio recording of an answer, and saves it locally as an answer to a specific question
require('_include.php');

header('Content-Type: text/plain; charset=utf-8'); // return plain text for JS parsing of result
try {
	if (!isset($_POST['k'])) {
		throw new RuntimeException('Incorrect key 1 (answer upload)');
	}
	if (strcmp($ANSWER_APP_KEY, $_POST['k']) !== 0) {
		throw new RuntimeException('Incorrect key 2 (answer upload)');
	}

	if (!isset($_POST['id'])) {
		throw new RuntimeException('Question number not set (answer upload)');
	}
	$questionId = intval($_POST['id']);
	if ($questionId <= 0) {
		throw new RuntimeException('Question id error (answer upload)');
	}

	// check whether local question exists
	logMessage('Started upload of answer to question ' . $questionId . ' (answer upload)');
	$questionFile = preg_grep('~' . $questionId . '-.*\.mp3$~', scandir($BASE_HUMAN_QUESTION_DIRECTORY));
	if (!$questionFile) {
		throw new RuntimeException('Question id not found (answer upload)');
	}

	// undefined | multiple files | $_FILES corruption attack: invalid
	if (!isset($_FILES['answer']['error']) ||
		is_array($_FILES['answer']['error'])) {
		throw new RuntimeException('Unknown file error (answer upload)');
	}

	// $_FILES['answer']['error'] values mean various failures
	switch ($_FILES['answer']['error']) {
		case UPLOAD_ERR_OK:
			break;
		case UPLOAD_ERR_NO_FILE:
			throw new RuntimeException('No file sent (answer upload)'); // no file sent
		case UPLOAD_ERR_INI_SIZE:
		case UPLOAD_ERR_FORM_SIZE:
			throw new RuntimeException('Invalid file size (answer upload)'); // file too big
		default:
			throw new RuntimeException('Unknown error (answer upload)');
	}

	// check filesize
	if ($_FILES['answer']['size'] > 1500000) {
		throw new RuntimeException('File size error (answer upload)'); // file too big - 1000000 bytes is about 1.5 mins in m4a
	}

	// can't trust the given name, so just hash the file itself for a safe version
	// we keep need the id in the filename as well for answer parsing
	$fileName = sprintf('%s%d-%s.m4a',
		$BASE_ANSWER_DIRECTORY,
		$questionId,
		sha1_file($_FILES['answer']['tmp_name'])
	);

	// save this as an alternative response if the answer already exists
	$updateTime = round(microtime(true) * 1000);
	if(file_exists($fileName)) {
		// if the uploaded file size is identical to the existing file then this is likely a retry by the app - ignore (but return name)
		if ($_FILES['answer']['size'] == filesize($fileName)) {
			logMessage('Exact duplicate of answer to question ' . $questionId . ' - ignoring (answer upload)');

		// otherwise rename (archive) and reject
		} else {
			if (!move_uploaded_file(
				$_FILES['answer']['tmp_name'],
				$fileName . '.' . $updateTime . '.new'
			)) {
				throw new RuntimeException('Unknown save error on duplicate (answer upload)'); // failed to move file
			}

			throw new RuntimeException('Question already answered (answer upload)');
		}
	} else {
		if (!move_uploaded_file(
			$_FILES['answer']['tmp_name'],
			$fileName
		)) {
			throw new RuntimeException('Unknown save error (answer upload)'); // failed to move file
		}
	}

	$voiceFileUrl = $ROOT_URL . $fileName;
	logMessage('Upload completed for answer to question: ' . $questionId . ', ' . $voiceFileUrl . ' (answer upload)');
	echo $questionId . ',' . $voiceFileUrl;

} catch (RuntimeException $e) {
	logMessage('Answer upload error: ' . $e->getMessage());
	echo 'error';
}
?>
