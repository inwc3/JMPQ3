package systems.crigges.jmpq3;

import java.io.IOException;

/**
 * Signals that an MPQ archive is damaged, malformed, or uses a feature this
 * library does not support.
 */
public class JMpqException extends IOException {
    private static final long serialVersionUID = 2L;

    /**
     * @param msg description of the problem.
     */
    public JMpqException(String msg) {
        super(msg);
    }

    /**
     * @param msg   description of the problem.
     * @param cause the underlying failure; never discard it, callers need the
     *              stack trace to tell a corrupt archive from a bug.
     */
    public JMpqException(String msg, Throwable cause) {
        super(msg, cause);
    }

    /**
     * @param cause the underlying failure.
     */
    public JMpqException(Throwable cause) {
        super(cause);
    }
}
