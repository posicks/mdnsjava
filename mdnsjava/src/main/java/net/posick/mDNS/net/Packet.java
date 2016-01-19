package net.posick.mDNS.net;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import net.posick.mDNS.utils.ExecutionTimer;

public class Packet
{
    private final InetAddress address;
    
    private final int port;
    
    private final byte[] data;
    
    protected static int sequence;
    
    protected int id;
    
    protected ExecutionTimer timer = new ExecutionTimer();
    
    
    protected Packet(final DatagramPacket datagram)
    {
        this(datagram.getAddress(), datagram.getPort(), datagram.getData(), datagram.getOffset(), datagram.getLength());
    }
    
    
    protected Packet(final InetAddress address, final int port, final byte[] data, final int offset, final int length)
    {
        id = Packet.sequence++ ;
        this.address = address;
        this.port = port;
        this.data = data;
    }
    
    
    public InetAddress getAddress()
    {
        return address;
    }
    
    
    public byte[] getData()
    {
        return data;
    }
    
    
    public int getPort()
    {
        return port;
    }
    
    
    public SocketAddress getSocketAddress()
    {
        return new InetSocketAddress(address, port);
    }
}