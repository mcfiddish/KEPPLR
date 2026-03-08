package kepplr.stars;

/**
 * Runtime exception that indicates a catalog lookup has failed for some reason.
 *
 * @author F.S.Turner
 */
public class StarCatalogLookupException extends RuntimeException {

    /** Default serial version UID. */
    private static final long serialVersionUID = 1L;

    public StarCatalogLookupException() {
        super();
    }

    public StarCatalogLookupException(String message, Throwable cause) {
        super(message, cause);
    }

    public StarCatalogLookupException(String message) {
        super(message);
    }

    public StarCatalogLookupException(Throwable cause) {
        super(cause);
    }
}
