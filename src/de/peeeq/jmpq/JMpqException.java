package de.peeeq.jmpq;

import java.io.IOException;

public class JMpqException extends IOException {
	private static final long serialVersionUID = 1L;

	public JMpqException(String msg) {
		super(msg);
	}

	public JMpqException() {
		super();
	}

	public JMpqException(Throwable t) {
		super(t);
	}
}
