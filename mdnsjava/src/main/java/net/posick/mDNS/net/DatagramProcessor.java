package net.posick.mDNS.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.logging.Level;

import org.xbill.DNS.Options;

public class DatagramProcessor extends NetworkProcessor
{
    // The default UDP datagram payload size
    protected int maxPayloadSize = 512;
    
    protected boolean isMulticast = false;
    
    protected boolean loopbackModeDisabled = false;
    
    protected boolean reuseAddress = true;
    
    protected int ttl = 255;
    
    protected DatagramSocket socket;
    
    private long lastPacket;
    
    
    public DatagramProcessor(final InetAddress ifaceAddress, final InetAddress address, final int port, final PacketListener listener)
    throws IOException
    {
        super(ifaceAddress, address, port, listener);
        
        if (address != null)
        {
            isMulticast = address.isMulticastAddress();
        }
        
        NetworkInterface netIface = null;
        if (isMulticast)
        {
            MulticastSocket socket = new MulticastSocket(port);
            
            // Set the IP TTL to 255, per the mDNS specification [RFC 6762].
            String temp;
            if ((temp = Options.value("mdns_multicast_loopback")) != null && temp.length() > 0)
            {
                loopbackModeDisabled = "true".equalsIgnoreCase(temp) || "t".equalsIgnoreCase(temp) || "yes".equalsIgnoreCase(temp) || "y".equalsIgnoreCase(temp);
            }
            
            if ((temp = Options.value("mdns_socket_ttl")) != null && temp.length() > 0)
            {
                try
                {
                    ttl = Integer.valueOf(temp);
                } catch (NumberFormatException e)
                {
                    // ignore
                }
            }
            
            /*
            if ((temp = Options.value("mdns_reuse_address")) != null && temp.length() > 0)
            {
                reuseAddress = "true".equalsIgnoreCase(temp) || "t".equalsIgnoreCase(temp) || "yes".equalsIgnoreCase(temp) || "y".equalsIgnoreCase(temp);
            }
            */
            reuseAddress = true;
            
            socket.setLoopbackMode(loopbackModeDisabled);
            socket.setReuseAddress(reuseAddress);
            socket.setTimeToLive(ttl);
            
            socket.setInterface(ifaceAddress);
            
            socket.joinGroup(address);
            
            this.socket = socket;
        } else
        {
            socket = new DatagramSocket(new InetSocketAddress(ifaceAddress, port));
        }
        
        netIface = NetworkInterface.getByInetAddress(ifaceAddress);
        
        // Determine maximum mDNS Payload size
        if (netIface == null)
        {
            netIface = NetworkInterface.getByInetAddress(socket.getLocalAddress());
            if (netIface == null)
            {
                InetAddress addr = socket.getInetAddress();
                if (addr != null)
                {
                    netIface = NetworkInterface.getByInetAddress(addr);
                }
            }
        }
        
        if (netIface != null)
        {
            try
            {
                mtu = netIface.getMTU();
            } catch (SocketException e)
            {
                netIface = null;
                logger.logp(Level.WARNING, getClass().getName(), "DatagramProcessor.<init>", "Error getting MTU from Network Interface " + netIface + ". Using default MTU.");
            }
        }
        
        if (netIface == null)
        {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            int smallestMtu = DEFAULT_MTU;
            while (ifaces.hasMoreElements())
            {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isLoopback() && !iface.isVirtual() && iface.isUp())
                {
                    int mtu = iface.getMTU();
                    if (mtu < smallestMtu)
                    {
                        smallestMtu = mtu;
                    }
                }
            }
            mtu = smallestMtu;
        }
        
        maxPayloadSize = mtu - 40 /* IPv6 Header Size */- 8 /* UDP Header */;
    }
    
    
    @Override
    public void close()
    throws IOException
    {
        super.close();
        
        if (isMulticast)
        {
            try
            {
                ((MulticastSocket) socket).leaveGroup(address);
            } catch (SecurityException e)
            {
                logger.log(Level.WARNING, "A Security error occurred while leaving Multicast Group \"" + address.getAddress() + "\" - " + e.getMessage(), e);
            } catch (Exception e)
            {
                logger.log(Level.WARNING, "Error leaving Multicast Group \"" + address.getAddress() + "\" - " + e.getMessage(), e);
            }
        }
        
        socket.close();
    }
    
    
    public boolean isLoopbackModeDisabled()
    {
        return loopbackModeDisabled;
    }


    public boolean isReuseAddress()
    {
        return reuseAddress;
    }
    
    
    public int getTTL()
    {
        return ttl;
    }
    
    
    public int getMaxPayloadSize()
    {
        return maxPayloadSize;
    }
    
    
    public boolean isMulticast()
    {
        return isMulticast;
    }
    
    
    @Override
    public boolean isOperational()
    {
        return super.isOperational() && socket.isBound() && !socket.isClosed() && (lastPacket <= (System.currentTimeMillis() + 120000));
    }
    
    
    public void run()
    {
        lastPacket = System.currentTimeMillis();
        while (!exit)
        {
            try
            {
                byte[] buffer = new byte[mtu];
                final DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
                socket.receive(datagram);
                lastPacket = System.currentTimeMillis();
                if (datagram.getLength() > 0)
                {
                    Packet packet = new Packet(datagram);
                    if (logger.isLoggable(Level.FINE))
                    {
                        logger.logp(Level.FINE, getClass().getName(), "run", "-----> Received packet " + packet.id + " <-----");
                        packet.timer.start();
                    }
                    executors.executeNetworkTask(new PacketRunner(listener, packet));
                }
            } catch (SecurityException e)
            {
                logger.log(Level.WARNING, "Security issue receiving data from \"" + address + "\" - " + e.getMessage(), e);
            } catch (Exception e)
            {
                if (!exit || logger.isLoggable(Level.FINE))
                {
                    logger.log(Level.WARNING, "Error receiving data from \"" + address + "\" - " + e.getMessage(), e);
                }
            }
        }
    }
    
    
    @Override
    public void send(final byte[] data)
    throws IOException
    {
        if (exit)
        {
            return;
        }
        
        DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
        
        try
        {
            if (isMulticast)
            {
                // Set the IP TTL to 255, per the mDNS specification [RFC 6762].
                ((MulticastSocket) socket).setTimeToLive(255);
            }
            socket.send(packet);
        } catch (IOException e)
        {
            logger.log(Level.FINE, "Error sending datagram to \"" + packet.getSocketAddress() + "\".", e);
            
            if ("no route to host".equalsIgnoreCase(e.getMessage()))
            {
                close();
            }
            
            IOException ioe = new IOException("Exception \"" + e.getMessage() + "\" occured while sending datagram to \"" + packet.getSocketAddress() + "\".", e);
            ioe.setStackTrace(e.getStackTrace());
            throw ioe;
        }
    }
    
    
    @Override
    protected void finalize()
    throws Throwable
    {
        close();
        super.finalize();
    }
}
