<?php
// this API lists either the answers received within the last 10 minutes, the unanswered questions, or a single task by id
require('_include.php');

header('Content-Type: text/plain; charset=utf-8'); // return plain text for JS parsing of result
try {
	if (!isset($_GET['k'])) {
		throw new RuntimeException('Incorrect key 1 (response list)');
	}
	if (strcmp($SPEECH_APPLIANCE_KEY, $_GET['k']) !== 0 && strcmp($ANSWER_APP_KEY, $_GET['k']) !== 0) {
		throw new RuntimeException('Incorrect key 2 (response list)');
	}

	if (isset($_GET['id'])) {
		$requestedId = intval($_GET['id']);
		if ($requestedId <= 0) {
			throw new RuntimeException('Requested id error (single response list)');
		}

		logMessage('Started single response retrieval for question ' . $requestedId . ' (single response list)');
		$questionFile = preg_grep('~' . $requestedId . '-.*\.mp3$~', scandir($BASE_QUESTION_DIRECTORY));
		if ($questionFile) {
			$answerFile = preg_grep('~' . $requestedId . '-.*\.m4a$~', scandir($BASE_ANSWER_DIRECTORY));
			if ($answerFile) {
				// need to loop as preg_grep returns modified original array and we don't know the original position
				foreach ($answerFile as $item) {
					$foundAnswer = $ROOT_URL . $BASE_ANSWER_DIRECTORY . $item;
					logMessage('Returning response for question ' . $requestedId . ': ' . $foundAnswer . ' (single response list)');
					echo $requestedId . ',' . $foundAnswer;
					exit;
				}
			} else {
				logMessage('Returning response not ready for question ' . $requestedId . ' (single response list)');
				echo 'unanswered';
				exit;
			}
		}

		throw new RuntimeException('Single response not found error (single response list)');

	} else {
		if (!isset($_GET['type'])) {
			throw new RuntimeException('Incorrect type 1 (response list)');
		}

		if (strcasecmp($_GET['type'], 'answered') === 0) {
			logMessage('Started answered question list retrieval (response list)');
			$answerFiles = preg_grep('~\d{4}-.*\.m4a$~', scandir($BASE_ANSWER_DIRECTORY));
			$echoed = false;

			echo '[';
			foreach($answerFiles as $item) {
				// to save bandwidth, only include files modified within the last 10 minutes
				if ((time() - filemtime($BASE_ANSWER_DIRECTORY . $item)) < $RECENT_ANSWERS_LIST_TIME) {
					$fileParts = explode('-', $item);
					echo ($echoed ? ',' : '') . '{"id":' . explode('-', $item)[0] . ',"url":"' . $ROOT_URL . $BASE_ANSWER_DIRECTORY . $item . '"}';
					$echoed = true;
				}
			}
			echo ']';
			logMessage('Answered question list success (response list)');

		} else if (strcasecmp($_GET['type'], 'unanswered') === 0) {
			logMessage('Started unanswered question list retrieval (response list)');
			$questionFiles = preg_grep('~\d{4}-.*\.mp3$~', scandir($BASE_QUESTION_DIRECTORY));
			$answerFiles = preg_grep('~\d{4}-.*\.m4a$~', scandir($BASE_ANSWER_DIRECTORY));
			$reservedFiles = preg_grep('~\d{4}-\d+\.reserved$~', scandir($BASE_ANSWER_DIRECTORY));
			$echoed = false;

			$answeredQuestions = array();
			foreach($answerFiles as $item) {
				$answeredQuestions[] = intval(explode('-', $item)[0]);
			}
			foreach($reservedFiles as $item) {
				if ((time() - filemtime($BASE_ANSWER_DIRECTORY . $item)) < $QUESTION_RESERVATION_TIME) {
					$previousRequestSource = intval(explode('-', $item)[1]);
					if ($REQUEST_SOURCE !== $previousRequestSource) {
						$answeredQuestions[] = intval(explode('-', $item)[0]); // still reserved, and not by us
					}
				} else {
					unlink($BASE_ANSWER_DIRECTORY . $item); // remove this reservation
				}
			}

			echo '[';
			foreach($questionFiles as $item) {
				$questionNumber = intval(explode('-', $item)[0]);
				if (!in_array($questionNumber, $answeredQuestions)) {
					echo ($echoed ? ',' : '') . '{"id":' . $questionNumber . ',"url":"' . $ROOT_URL . $BASE_QUESTION_DIRECTORY . $item . '"}';
					$echoed = true;
				}
			}
			echo ']';
			logMessage('Unanswered question list success (response list)');
		
		} else {
			throw new RuntimeException('No answer type given (response list)');
		}
	}

} catch (RuntimeException $e) {
	logMessage('Answer response error: ' . $e->getMessage());
	echo 'error';
}
?>