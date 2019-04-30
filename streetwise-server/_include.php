<?php

require('_globals.php');

$_SESSION = array();
session_start();

function logMessage($message) {
	global $LOG_FILE_PATH;
	if (empty($LOG_FILE_PATH)) {
		return; //logging is disabled
	}
	
	global $REQUEST_SOURCE;
	file_put_contents($LOG_FILE_PATH . 'streetwise.log', '[' . round(microtime(true) * 1000) . ' : ' . session_id() . ' : ' . $REQUEST_SOURCE . ']: ' . $message . "\n", FILE_APPEND | LOCK_EX);
}

$REQUEST_SOURCE = -1; // for identification of individual applicance and app instances
if (isset($_REQUEST['source'])) {
	$REQUEST_SOURCE = intval($_REQUEST['source']);
}

if ($REQUEST_SOURCE < 0) {
	logMessage('General request error: No request source specified');
	echo 'error';
	exit;
}

?>
