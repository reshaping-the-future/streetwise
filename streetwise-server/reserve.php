<?php
// this API reserves a question for a particular requestSource (e.g., person) to answer
require('_include.php');

header('Content-Type: text/plain; charset=utf-8'); // return plain text for JS parsing of result
try {
	if (!isset($_GET['k'])) {
		throw new RuntimeException('Incorrect key 1 (question reservation)');
	}
	if (strcmp($ANSWER_APP_KEY, $_GET['k']) !== 0) {
		throw new RuntimeException('Incorrect key 2 (question reservation)');
	}

	if (!isset($_GET['id'])) {
		throw new RuntimeException('Question number not set (question reservation)');
	}
	$questionId = intval($_GET['id']);
	if ($questionId <= 0) {
		throw new RuntimeException('Question id error (question reservation)');
	}

	// check whether local question (and answer) exist
	logMessage('Started question reservation (question reservation)');
	$questionFile = preg_grep('/^' . $questionId . '-.*\.mp3$/', scandir($BASE_QUESTION_DIRECTORY));
	if (count($questionFile) >= 1) {
		$answerFile = preg_grep('/^' . $questionId . '-.*\.m4a$/', scandir($BASE_ANSWER_DIRECTORY));
		if (count($answerFile) >= 1) {
			throw new RuntimeException('Question has already been answered (question reservation)');
		}
	} else {
		throw new RuntimeException('Question id not found (question reservation)');
	}

	// reserve this question - note that this is also where we clean up previous reservation files
	$reservedFiles = preg_grep('/^' . $questionId . '-\d+\.reserved$/', scandir($BASE_ANSWER_DIRECTORY));
	if (count($reservedFiles) <= 0) {
		touch($BASE_ANSWER_DIRECTORY . $questionId . '-' . $REQUEST_SOURCE . '.reserved');
		echo 'success';
		logMessage('Reserved question ' . $questionId . ' for ' . $REQUEST_SOURCE . ' (question reservation)');

	} else {
		foreach($reservedFiles as $item) {
			$previousRequestSource = intval(explode('-', $item)[1]);
			if ((time() - filemtime($BASE_ANSWER_DIRECTORY . $item)) < $QUESTION_RESERVATION_TIME) {
				if ($REQUEST_SOURCE == $previousRequestSource) {
					echo 'success';
					logMessage('Reservation of ' . $questionId . ' for ' . $REQUEST_SOURCE . ' accepted for same source (question reservation)');
				} else {
					throw new RuntimeException('Not reserving question ' . $questionId . ' for ' . $REQUEST_SOURCE . ': already reserved for ' . $previousRequestSource . ' (question reservation)');
				}
			} else {
				unlink($BASE_ANSWER_DIRECTORY . $item); // remove this reservation
				touch($BASE_ANSWER_DIRECTORY . $questionId . '-' . $REQUEST_SOURCE . '.reserved');
				echo 'success';
				logMessage('Cancelled existing reservation of question ' . $questionId . ' for ' . $previousRequestSource . ' and re-reserved for ' . $REQUEST_SOURCE . ' (question reservation)');
			}
		}
	}

} catch (RuntimeException $e) {
	logMessage('Question reservation error: ' . $e->getMessage());
	echo 'error';
}
?>