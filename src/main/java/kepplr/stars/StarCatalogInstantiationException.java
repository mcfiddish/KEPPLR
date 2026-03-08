package kepplr.stars;

/**
 * Exception used to indicate a failure creating a star catalog.
 *
 * @author F.S.Turner
 */
public class StarCatalogInstantiationException extends Exception {

    /** Default serial version UID. */
    private static final long serialVersionUID = 1L;

    public StarCatalogInstantiationException() {
        super();
    }

    public StarCatalogInstantiationException(String message, Throwable cause) {
        super(message, cause);
    }

    public StarCatalogInstantiationException(String message) {
        super(message);
    }

    public StarCatalogInstantiationException(Throwable cause) {
        super(cause);
    }
}
