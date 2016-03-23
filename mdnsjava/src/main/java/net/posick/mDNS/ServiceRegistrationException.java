package net.posick.mDNS;

import java.io.IOException;

/**
 * The Service Registration Exception is raised whenever there is a failure registering a service.
 * There are numerous causes for such a failure, such as an invalid service name and missing 
 * service information.
 * 
 * @author Steve Posick
 */
public class ServiceRegistrationException extends IOException
{
    private static final long serialVersionUID = 201603191238L;
    

    public static enum REASON {NO_INSTANCE_NAME, NO_TARGET, SERVICE_NAME_ALREADY_EXISTS, UNKNOWN}


    private REASON reason;


    public ServiceRegistrationException(REASON reason)
    {
        super();
        this.reason = reason;
    }


    public ServiceRegistrationException(REASON reason, String message, Throwable cause)
    {
        super(message, cause);
        this.reason = reason;
    }


    public ServiceRegistrationException(REASON reason, String message)
    {
        super(message);
        this.reason = reason;
    }


    public ServiceRegistrationException(REASON reason, Throwable cause)
    {
        super(cause);
        this.reason = reason;
    }


    /**
     * Returns the reason for the service registration failure.
     * 
     * @return The reason for the service registration failure
     */
    public REASON getReason()
    {
        return reason;
    }


    /**
     * Sets the reason for the service registration failure.
     * 
     * @param reason The reason for the service registration failure
     */
    public void setReason(REASON reason)
    {
        this.reason = reason;
    }


    @Override
    public String toString()
    {
        String s = getClass().getName();
        String message = getLocalizedMessage();
        return (message != null) ? (s + ": [Reason: " + reason + "] " + message) : s + ": [Reason: " + reason + "]";
    };
}
