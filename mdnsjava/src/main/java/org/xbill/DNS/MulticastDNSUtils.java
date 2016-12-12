package org.xbill.DNS;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.posick.mDNS.utils.Misc;

@SuppressWarnings({"rawtypes", "unchecked"})
public class MulticastDNSUtils
{
    private static final Logger logger = Misc.getLogger(MulticastDNSUtils.class, Options.check("mdns_verbose"));

    
    public static final Record[] EMPTY_RECORDS = new Record[0];
    
    
    /**
     * Tests if the response message answers all of the questions within the query message.
     * 
     * @param query The query message
     * @param response The response message
     * @return True if the response message answers all of the questions in the query message.
     */
    public static boolean answersAll(final Message query, final Message response)
    {
        switch (response.getHeader().getOpcode())
        {
            case Opcode.QUERY :
            case Opcode.IQUERY :
            case Opcode.NOTIFY :
            case Opcode.STATUS :
                int index = 0;
                Record[] qRecords = MulticastDNSUtils.extractRecords(query, Section.QUESTION);
                Record[] rRecords = MulticastDNSUtils.extractRecords(response, Section.QUESTION);
                boolean[] similarArray = new boolean[qRecords.length];
                for (Record qRecord : qRecords)
                {
                    similarArray[index] = false;
                    for (Record rRecord : rRecords)
                    {
                        if (qRecord.getName().equals(rRecord.getName()) &&
                        ((rRecord.getType() == Type.ANY) || (qRecord.getType() == rRecord.getType())))
                        {
                            similarArray[index] = true;
                            break;
                        }
                    }
                    index++;
                }
                
                for (boolean similar : similarArray)
                {
                    if (!similar)
                    {
                        return false;
                    }
                }
                return true;
        }
        
        return false;
    }
    
    
    /**
     * Tests if the response message answers any of the questions within the query message.
     * 
     * @param query The query message
     * @param response The response message
     * @return True if the response message answers any of the questions in the query message.
     */
    public static boolean answersAny(final Message query, final Message response)
    {
        Header h = response.getHeader();
        
        if (!h.getFlag(Flags.QR))
        {
            return false;
        }
        
        switch (h.getOpcode())
        {
            case Opcode.QUERY :
            case Opcode.IQUERY :
            case Opcode.NOTIFY :
            case Opcode.STATUS :
                Record[] qRecords = MulticastDNSUtils.extractRecords(query, Section.QUESTION);
                Record[] rRecords = MulticastDNSUtils.extractRecords(response, Section.ANSWER, Section.ADDITIONAL, Section.AUTHORITY);
                for (Record qRecord : qRecords)
                {
                    for (Record rRecord : rRecords)
                    {
                        if (qRecord.getName().equals(rRecord.getName()) &&
                        ((qRecord.getType() == Type.ANY) || (qRecord.getType() == rRecord.getType())))
                        {
                            return true;
                        }
                    }
                }
        }
        
        return false;
    }
    
    
    public static Record clone(final Record record)
    {
        return record.cloneRecord();
    }
    
    
    public static Record[] extractRecords(final Message message, final int... sections)
    {
        Record[] records = EMPTY_RECORDS;
        
        for (int section : sections)
        {
            Record[] tempRecords = message.getSectionArray(section);
            if ((tempRecords != null) && (tempRecords.length > 0))
            {
                int size = records.length + tempRecords.length;
                Record[] newRecords = new Record[size];
                System.arraycopy(records, 0, newRecords, 0, records.length);
                System.arraycopy(tempRecords, 0, newRecords, records.length, tempRecords.length);
                records = newRecords;
            }
        }
        
        return records;
    }
    
    
    public static final Record[] extractRecords(final RRset rrset)
    {
        if (rrset == null)
        {
            return new Record[0];
        }
        
        final Record[] results = new Record[rrset.size()];
        
        if (results.length > 0)
        {
            int index = 0;
            Iterator iterator = rrset.rrs(false);
            if (iterator != null)
            {
                while (iterator.hasNext())
                {
                    results[index++] = (Record) iterator.next();
                }
            }
        }
        
        return results;
    }
    
    
    public static final Record[] extractRecords(final RRset[] rrs)
    {
        if ((rrs == null) || (rrs.length == 0))
        {
            return MulticastDNSUtils.EMPTY_RECORDS;
        }
        
        int capacity = 0;
        for (RRset rr : rrs)
        {
            capacity += rr.size();
        }
        final Record[] results = new Record[capacity];
        
        int index = 0;
        for (RRset rr : rrs)
        {
            Record[] records = extractRecords(rr);
            for (Record record : records)
            {
                results[index++] = record;
            }
        }
        
        return results;
    }
    
    
    public static String getHostName()
    {
        String hostname = System.getenv().get("HOSTNAME");
        if ((hostname == null) || (hostname.trim().length() == 0))
        {
            hostname = System.getenv().get("COMPUTERNAME");
        }
        
        if ((hostname == null) || (hostname.trim().length() == 0))
        {
            try
            {
                InetAddress localhost = InetAddress.getLocalHost();
                hostname = localhost.getHostName();
                
                if ((hostname == null) || hostname.startsWith("unknown"))
                {
                    hostname = localhost.getCanonicalHostName();
                }
            } catch (UnknownHostException e)
            {
            }
        }
        
        return hostname;
    }
    
    
    public static InetAddress[] getLocalAddresses()
    {
        ArrayList addresses = new ArrayList();
        
        try
        {
            Enumeration<NetworkInterface> enet = NetworkInterface.getNetworkInterfaces();
            
            while (enet.hasMoreElements())
            {
                NetworkInterface net = enet.nextElement();
                
                if (net.isLoopback())
                {
                    continue;
                }
                
                Enumeration<InetAddress> eaddr = net.getInetAddresses();
                
                while (eaddr.hasMoreElements())
                {
                    addresses.add(eaddr.nextElement());
                }
            }
        } catch (SocketException e)
        {
            // ignore
        }
        
        return (InetAddress[]) addresses.toArray(new InetAddress[addresses.size()]);
    }
    
    
    public static String getMachineName()
    {
        String name = null;
        
        try
        {
            Enumeration<NetworkInterface> enet = NetworkInterface.getNetworkInterfaces();
            
            while (enet.hasMoreElements() && (name == null))
            {
                NetworkInterface net = enet.nextElement();
                
                if (net.isLoopback())
                {
                    continue;
                }
                
                Enumeration<InetAddress> eaddr = net.getInetAddresses();
                
                while (eaddr.hasMoreElements())
                {
                    InetAddress inet = eaddr.nextElement();
                    
                    if (inet.getCanonicalHostName().equalsIgnoreCase(inet.getHostAddress()) == false)
                    {
                        name = inet.getCanonicalHostName();
                        break;
                    }
                }
            }
        } catch (SocketException e)
        {
            // ignore
        }
        
        return name;
    }
    
    
    public static Name getTargetFromRecord(final Record record)
    {
        if (record instanceof SingleNameBase)
        {
            return ((SingleNameBase) record).getSingleName();
        } else
        {
            try
            {
                Method method = record.getClass().getMethod("getTarget", new Class[0]);
                if (method != null)
                {
                    Object target = method.invoke(record, new Object[0]);
                    if (target instanceof Name)
                    {
                        return (Name) target;
                    }
                }
            } catch (Exception e)
            {
                logger.logp(Level.FINE, MulticastDNSUtils.class.getName(), "getTargetFromRecord", "No target specified in record " +  record.getClass().getSimpleName() + ": " + record);
            }
        }
        
        return null;
    }
    
    
    /**
     * Compares the 2 messages and determines if they are equal.
     * 
     * @param message1
     * @param message2
     * @return True if the messages are equal
     */
    public static boolean messagesEqual(final Message message1, final Message message2)
    {
        if (message1 == message2)
        {
            return true;
        } else if ((message1 == null) || (message2 == null))
        {
            return false;
        } else
        {
            boolean headerEqual;
            Header responseHeader = message1.getHeader();
            Header queryHeader = message2.getHeader();
            
            if (responseHeader == queryHeader)
            {
                headerEqual = false;
            } else if ((responseHeader == null) || (queryHeader == null))
            {
                headerEqual = false;
            } else
            {
                boolean[] responseFlags = responseHeader.getFlags();
                boolean[] queryFlags = queryHeader.getFlags();
                if (!Arrays.equals(responseFlags, queryFlags))
                {
                    return false;
                }
                
                headerEqual = (responseHeader.getOpcode() == queryHeader.getOpcode()) &&
                (responseHeader.getRcode() == queryHeader.getRcode());
            }
            
            return headerEqual && Arrays.equals(MulticastDNSUtils.extractRecords(message2, 0, 1, 2, 3), MulticastDNSUtils.extractRecords(message1, 0, 1, 2, 3));
        }
    }
    
    
    public static Message newQueryResponse(final Record[] records, final int section)
    {
        Message message = new Message();
        Header header = message.getHeader();
        
        header.setRcode(Rcode.NOERROR);
        header.setOpcode(Opcode.QUERY);
        header.setFlag(Flags.QR);
        
        for (int index = 0; index < records.length; index++)
        {
            message.addRecord(records[index], section);
        }
        
        return message;
    }
    
    
    public static void setDClassForRecord(final Record record, final int dclass)
    {
        record.dclass = dclass;
    }
    
    
    public static void setTLLForRecord(final Record record, final long ttl)
    {
        record.setTTL(ttl);
    }
    
    
    public static Message[] splitMessage(final Message message)
    {
        List messages = new ArrayList();
        
        int maxRecords = Options.intValue("mdns_max_records_per_message");
        if (maxRecords > 1)
        {
            maxRecords = 10;
        }
        
        Message m = null;
        for (int section : new int[]{0, 1, 2, 3})
        {
            Record[] records = message.getSectionArray(section);
            for (int index = 0; index < records.length; index++)
            {
                if (m == null)
                {
                    m = new Message();
                    Header header = (Header) message.getHeader().clone();
                    //                    header.setFlag(Flags.TC);
                    header.setCount(0, 0);
                    header.setCount(1, 0);
                    header.setCount(2, 0);
                    header.setCount(3, 0);
                    m.setHeader(header);
                    m.addRecord(records[index], section);
                } else
                {
                    m.addRecord(records[index], section);
                }
                
                // Only aggregate "mdns_max_records_per_message" or 10 questions into a single, to prevent large messages.
                if ((index != 0) && ((index % maxRecords) == 0))
                {
                    messages.add(m);
                    m = null;
                }
            }
        }
        
        return (Message[]) messages.toArray(new Message[messages.size()]);
    }
}
