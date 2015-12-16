package net.posick.mDNS.utils;

import java.util.EmptyStackException;
import java.util.Stack;
import java.util.concurrent.TimeUnit;

@SuppressWarnings({"rawtypes", "unchecked"})
public class ExecutionTimer
{
    private static ExecutionTimer timer = new ExecutionTimer();
    
    private final Stack stack = new Stack();
    
    
    public ExecutionTimer()
    {
    }
    
    
    public long start()
    {
        return ((Long) stack.push(new Long(System.nanoTime()))).longValue();
    }
    
    
    public double took(final TimeUnit unit)
    {
        try
        {
            long start = ((Long) stack.pop()).longValue();
            long took = System.nanoTime() - start;
            
            switch (unit)
            {
                case DAYS:
                    return (double) took / (double) 86400000000000l;
                    // return (double) took / 86400000000000f;
                case HOURS:
                    return (double) took / (double) 3600000000000l;
                    // return (double) took / 60000000000f;
                case MICROSECONDS:
                    return (double) took / (double) 1000;
                    // return (double) took / 1000f;
                case MILLISECONDS:
                    return (double) took / (double) 1000000;
                    // return (double) took / 1000000f;
                case MINUTES:
                    return (double) took / (double) 60000000000l;
                    // return (double) took / 60000000000f;
                case NANOSECONDS:
                    return took;
                case SECONDS:
                    return (double) took / (double) 1000000000;
                    // return (double) took / 1000000000f;
            }
        } catch (EmptyStackException e)
        {
            // ignore
        }
        
        return 0;
    }
    
    
    public static long _start()
    {
        return timer.start();
    }
    
    
    public static double _took(final TimeUnit unit)
    {
        return timer.took(unit);
    }
}
