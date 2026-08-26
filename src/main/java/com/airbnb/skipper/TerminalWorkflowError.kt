package com.airbnb.skipper

/** Error indicating a workflow has already reached a terminal or compensation state. */
class TerminalWorkflowError(message: String) : IllegalStateException(message)
