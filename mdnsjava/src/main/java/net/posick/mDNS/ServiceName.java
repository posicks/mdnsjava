package net.posick.mDNS;

import java.text.DecimalFormat;

import org.xbill.DNS.Name;
import org.xbill.DNS.TextParseException;

import net.posick.mDNS.utils.Misc;

public class ServiceName extends Name
{
    private static final long serialVersionUID = 201305151047L;
    
    /* Used for printing non-printable characters */
    private static final DecimalFormat byteFormat = new DecimalFormat("000");
    
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
        int[] offsets = new int[labelCount];
        int offsetIndex = 0;
        
        StringBuilder domain = new StringBuilder();
        StringBuilder subType = new StringBuilder();
        StringBuilder subTypeIndicator = new StringBuilder();
        StringBuilder instance = new StringBuilder();

        boolean serviceName = false;
        
        // Traverse the labels to find the protocol specification (currently limited to _tcp or _udp),
        // the application, and any RFC 6763 subtypes. Supports RFC 2782 & RFC 6763
        // Notes: All name parts before the first name part beginning with an underscore "_" are parts of the domain name.
        //        All name parts after the first name part beginning with an underscore "_" are parts of the service name.
        int index;
        loop:
        for (index = labelCount - 1; index > 0; index--)
        {
            labels[index] = name.getLabel(index);
            if ((labels[index][0] > 0) && (labels[index][1] == '_'))
            {
                serviceName = true;
            }
            
            if (serviceName)
            {
                offsets[offsetIndex] = index;
                switch (offsetIndex)
                {
                    case 0:
                        protocol = byteString(labels[offsets[0]]);
                        break;
                    case 1:
                        application = byteString(labels[offsets[1]]);
                        type = application + "." + protocol;
                        break;
                    default:
                        if (labels[index][0] > 0) // if Length > 0
                        { 
                            if (labels[index][1] == '_') // if 1st character is an underscore '_'
                            {
                                String namePart = byteString(labels[index]);
                                if ("_sub".equals(namePart))
                                {
                                    subTypeIndicator.insert(0, namePart + ".");
                                } else
                                {
                                    subType.insert(0, namePart + ".");
                                }
                            } else
                            {
                                break loop;
                            }
                        }
                        break;
                }
                offsetIndex++;
            } else
            {
                if (labels[index][0] == 0)
                {
                    domain.insert(0, ".");
                } else
                {
                    domain.insert(0, byteString(labels[index]));
                }
            }
        }
        
        for (; index >= 0; index--)
        {
            labels[index] = name.getLabel(index);
            if (labels[index][0] > 0)
            {
                String namePart = byteString(labels[index]);
                if (labels[index][1] == '_')
                {
                    subType.insert(0, namePart + ".");
                } else
                {
                    instance.insert(0, namePart + ".");
                }
            }
        }
        
        this.fullSubType = Misc.trimTrailingDot(subType.toString() + (subTypeIndicator.length() > 0 ? subTypeIndicator.toString() : ""));
        this.subType = Misc.trimTrailingDot(subType).toString();
        this.instance = Misc.trimTrailingDot(instance).toString();
        this.domain = domain.toString();
        this.fullType = this.instance + this.fullSubType + this.type;
        
        serviceTypeName = new Name(this.fullType + "." + this.domain);
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
//        Name serviceName = new Name(args.length > 0 ? args[0] : "Steve Posick's Work MacBook Pro._test._sub._syncmate._tcp.local.");
        Name serviceName = new Name(args.length > 0 ? args[0] : "steve.posick._steve._test._sub._syncmate._tcp.local.");
        
        ServiceName name = new ServiceName(serviceName);
        System.out.println("Service Name = " + name);
        System.out.println("Instance: " + name.instance);
        System.out.println("Full Type: " + name.fullType);
        System.out.println("Sub Type: " + name.subType);
        System.out.println("Type: " + name.type);
        System.out.println("Application: " + name.application);
        System.out.println("Protocol: " + name.protocol);
        System.out.println("Domain: " + name.domain);
        
        int iterations = 100000;
        long startNanos = System.nanoTime();
        for (int index = 0; index < iterations; index++ )
        {
            name = new ServiceName(serviceName);
        }
        long tookNanos = System.nanoTime() - startNanos;
        System.out.println("Took " + ((double) tookNanos / (double) 1000000) + " milliseonds to parse " + iterations + " service names at " + (tookNanos / iterations) + " nanoseconds each name");
    }
}
