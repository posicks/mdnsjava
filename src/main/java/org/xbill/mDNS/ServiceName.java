package org.xbill.mDNS;

import java.text.DecimalFormat;

import org.xbill.DNS.Name;
import org.xbill.DNS.TextParseException;

public class ServiceName extends Name
{
    private static final long serialVersionUID = 201305151047L;
    
    /* Used for printing non-printable characters */
    private static final DecimalFormat byteFormat = new DecimalFormat();
    
    private String instance;
    
    private String fullSubType;
    
    private String subType;
    
    private String fullType;
    
    private String type;
    
    private String domain;
    
    private String protocol;
    
    private String application;
    
    private final Name serviceTypeName;
    
    
    public ServiceName(final String s)
    throws TextParseException
    {
        this(new Name(s));
    }
    
    
    public ServiceName(final String s, final Name name)
    throws TextParseException
    {
        this(new Name(s, name));
    }
    
    
    ServiceName(final Name name)
    throws TextParseException
    {
        super(name, 0);
        
        int labelCount = name.labels();
        byte[][] labels = new byte[labelCount][];
        int[] offsets = new int[4];
        int offsetLength = 0;
        
        // Traverse the labels to find the protocol specification (currently limited to _tcp or _udp).
        for (int index = labelCount - 1; index >= 0; index-- )
        {
            labels[index] = name.getLabel(index);
            if ((labels[index][0] > 0) && (labels[index][1] == '_'))
            {
                if (offsetLength > offsets.length)
                {
                    throw new TextParseException("Name \"" + name + "\" is not a RFC 2782 service name!");
                }
                
                offsets[offsetLength] = index;
                switch (offsetLength++ )
                {
                    case 0:
                        protocol = byteString(labels[offsets[0]]);
                        break;
                    case 1:
                        application = byteString(labels[offsets[1]]);
                        type = application + "." + protocol;
                        fullType = type;
                        break;
                    case 2:
                        break;
                    case 3:
                        StringBuilder sb = new StringBuilder();
                        for (int i = offsets[3]; i < offsets[2]; i++ )
                        {
                            sb.append(byteString(labels[i])).append(".");
                        }
                        sb.setLength(sb.length() - 1);
                        subType = sb.toString();
                        fullSubType = subType + "." + byteString(labels[offsets[2]]);
                        fullType = fullSubType + "." + type;
                        break;
                }
            }
        }
        
        if ((offsetLength <= 1) || (offsetLength == 3))
        {
            throw new TextParseException("Name \"" + name + "\" is not a RFC 2782 service name!");
        }
        
        // Determine Instance
        if (offsets[offsetLength - 1] > 0)
        {
            StringBuilder instance = new StringBuilder();
            for (int index = offsets[offsetLength - 1] - 1; index >= 0; index-- )
            {
                instance.append(byteString(labels[index]));
            }
            this.instance = instance.length() > 0 ? instance.toString() : null;
        }
        
        // Determine Domain
        if (offsets[0] > 0)
        {
            StringBuilder domain = new StringBuilder();
            for (int index = offsets[0] + 1; index < labels.length; index++ )
            {
                if ((labels[index] != null) && (labels[index][0] > 0))
                {
                    domain.append(byteString(labels[index])).append(".");
                }
            }
            this.domain = domain.toString();
        } else
        {
            domain = ".";
        }
        
        serviceTypeName = new Name(fullType + "." + domain);
    }
    
    
    public String getApplication()
    {
        return application;
    }
    
    
    public String getDomain()
    {
        return domain;
    }
    
    
    public String getFullSubType()
    {
        return fullSubType;
    }
    
    
    public String getFullType()
    {
        return fullType;
    }
    
    
    public String getInstance()
    {
        return instance;
    }
    
    
    public String getProtocol()
    {
        return protocol;
    }
    
    
    public Name getServiceTypeName()
    {
        return serviceTypeName;
    }
    
    
    public String getSubType()
    {
        return subType;
    }
    
    
    public String getType()
    {
        return type;
    }
    
    
    private String byteString(final byte[] array)
    {
        int pos = 0;
        StringBuilder sb = new StringBuilder();
        int len = array[pos++ ];
        for (int i = pos; i < (pos + len); i++ )
        {
            int b = array[i] & 0xFF;
            if ((b <= 0x20) || (b >= 0x7f))
            {
                sb.append('\\');
                sb.append(byteFormat.format(b));
            } else
            {
                switch (b)
                {
                    case '"':
                    case '(':
                    case ')':
                    case '.':
                    case ';':
                    case '\\':
                    case '@':
                    case '$':
                        sb.append('\\');
                        sb.append((char) b);
                        break;
                    default:
                        sb.append((char) b);
                        break;
                }
            }
        }
        return sb.toString();
    }
    
    
    public static void main(final String... args)
    throws TextParseException
    {
        Name serviceName = new Name(args.length > 0 ? args[0] : "Steve Posick\\226\\128\\153s Work MacBook Pro (posicks)_test._sub._syncmate._tcp.local.");
        
        ServiceName name = new ServiceName(serviceName);
        System.out.println("Service Name = " + name);
        System.out.println("Instance: " + name.instance);
        System.out.println("Full Type: " + name.fullType);
        System.out.println("Sub Type: " + name.subType);
        System.out.println("Type: " + name.type);
        System.out.println("Application: " + name.application);
        System.out.println("Protocol: " + name.protocol);
        System.out.println("Domain: " + name.domain);
        
        int iterations = 1000000;
        long startNanos = System.nanoTime();
        for (int index = 0; index < iterations; index++ )
        {
            name = new ServiceName(serviceName);
        }
        long tookNanos = System.nanoTime() - startNanos;
        System.out.println("Took " + ((double) tookNanos / (double) 1000000) + " milliseonds to parse " + iterations + " service names at " + (tookNanos / iterations) + " nanoseconds each name");
    }
}
