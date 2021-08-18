/*
 * 
 */
package systems.crigges.jmpq3;

import java.io.IOException;

/**
 * The Class JMpqException.
 */
public class JMpqException extends IOException {

    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Instantiates a new j mpq exception.
     *
     * @param msg the msg
     */
    public JMpqException(String msg) {
        super(msg);
    }

    /**
     * Instantiates a new j mpq exception.
     *
     * @param t the t
     */
    JMpqException(Throwable t) {
        super(t);
    }
}
