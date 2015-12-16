package net.posick.mDNS.utils;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.xbill.DNS.Options;

import net.posick.mDNS.net.NetworkProcessor;

public class Executors
{
    public static final int DEFAULT_NETWORK_THREAD_PRIORITY = Thread.NORM_PRIORITY + 2;
    
    public static final int CORE_THREADS_NETWORK_EXECUTOR = 5;
    
    public static final int MAX_THREADS_NETWORK_EXECUTOR = Integer.MAX_VALUE;
    
    public static final int TTL_THREADS_NETWORK_EXECUTOR = 10000;
    
    public static final int QUEUE_SIZE_NETWORK_EXECUTOR = 50;
    
    public static final int DEFAULT_CACHED_THREAD_PRIORITY = Thread.NORM_PRIORITY;
    
    public static final int CORE_THREADS_CACHED_EXECUTOR = 1;
    
    public static final int MAX_THREADS_CACHED_EXECUTOR = Integer.MAX_VALUE;
    
    public static final int TTL_THREADS_CACHED_EXECUTOR = 10000;
    
    public static final int QUEUE_SIZE_CACHED_EXECUTOR = 5;
    
    public static final int DEFAULT_SCHEDULED_THREAD_PRIORITY = Thread.NORM_PRIORITY;
    
    public static final int CORE_THREADS_SCHEDULED_EXECUTOR = 5;
    
    public static final int MAX_THREADS_SCHEDULED_EXECUTOR = Integer.MAX_VALUE;
    
    public static final int TTL_THREADS_SCHEDULED_EXECUTOR = 10000;
    
    public static final TimeUnit THREAD_TTL_TIME_UNIT = TimeUnit.MILLISECONDS;
    
    private static final ScheduledThreadPoolExecutor scheduledExecutor;
    
    private static final ThreadPoolExecutor executor;
    
    private static final ThreadPoolExecutor networkExecutor;
    
    static
    {
        scheduledExecutor = (ScheduledThreadPoolExecutor) java.util.concurrent.Executors.newScheduledThreadPool(CORE_THREADS_SCHEDULED_EXECUTOR, new ThreadFactory()
        {
            public Thread newThread(final Runnable r)
            {
                Thread t = new Thread(r, "mDNS Scheduled Thread");
                t.setDaemon(true);
                
                int threadPriority = DEFAULT_SCHEDULED_THREAD_PRIORITY;
                try
                {
                    String value = Options.value("mdns_scheduled_thread_priority");
                    if ((value == null) || (value.length() == 0))
                    {
                        value = Options.value("mdns_thread_priority");
                    }
                    if ((value != null) && (value.length() == 0))
                    {
                        threadPriority = Integer.parseInt(value);
                    }
                } catch (Exception e)
                {
                    // ignore
                }
                t.setPriority(threadPriority);
                t.setContextClassLoader(this.getClass().getClassLoader());
                return t;
            }
        });
        scheduledExecutor.setCorePoolSize(CORE_THREADS_SCHEDULED_EXECUTOR);
        scheduledExecutor.setMaximumPoolSize(MAX_THREADS_SCHEDULED_EXECUTOR);
        scheduledExecutor.setKeepAliveTime(TTL_THREADS_SCHEDULED_EXECUTOR, THREAD_TTL_TIME_UNIT);
        scheduledExecutor.allowCoreThreadTimeOut(true);
        
        int cacheExecutorQueueSize = QUEUE_SIZE_CACHED_EXECUTOR;
        try
        {
            String value = Options.value("mdns_cached_thread_queue_size");
            if ((value == null) || (value.length() == 0))
            {
                value = Options.value("mdns_thread_queue_size");
            }
            if ((value != null) && (value.length() > 0))
            {
                cacheExecutorQueueSize = Integer.parseInt(value);
            }
        } catch (Exception e)
        {
            // ignore
        }
        
        executor = new ThreadPoolExecutor(CORE_THREADS_CACHED_EXECUTOR, MAX_THREADS_CACHED_EXECUTOR,
        TTL_THREADS_CACHED_EXECUTOR, THREAD_TTL_TIME_UNIT,
        new ArrayBlockingQueue<Runnable>(cacheExecutorQueueSize),
        new ThreadFactory()
        {
            public Thread newThread(final Runnable r)
            {
                Thread t = new Thread(r, "mDNS Cached Thread");
                t.setDaemon(true);
                
                int threadPriority = DEFAULT_CACHED_THREAD_PRIORITY;
                try
                {
                    String value = Options.value("mdns_cached_thread_priority");
                    if ((value == null) || (value.length() == 0))
                    {
                        value = Options.value("mdns_thread_priority");
                    }
                    if ((value != null) && (value.length() == 0))
                    {
                        threadPriority = Integer.parseInt(value);
                    }
                } catch (Exception e)
                {
                    // ignore
                }
                t.setPriority(threadPriority);
                t.setContextClassLoader(NetworkProcessor.class.getClassLoader());
                return t;
            }
        }, new RejectedExecutionHandler()
        {
            public void rejectedExecution(final Runnable r, final ThreadPoolExecutor executor)
            {
                System.err.println("Network Processing Queue Rejected Packet it is FULL. [size: " + executor.getQueue().size() + "]");
            }
        });
        executor.setCorePoolSize(CORE_THREADS_CACHED_EXECUTOR);
        executor.setMaximumPoolSize(MAX_THREADS_CACHED_EXECUTOR);
        executor.setKeepAliveTime(TTL_THREADS_CACHED_EXECUTOR, THREAD_TTL_TIME_UNIT);
        executor.allowCoreThreadTimeOut(true);
        
        int networkExecutorQueueSize = QUEUE_SIZE_NETWORK_EXECUTOR;
        try
        {
            String value = Options.value("mdns_cached_thread_queue_size");
            if ((value == null) || (value.length() == 0))
            {
                value = Options.value("mdns_thread_queue_size");
            }
            if ((value != null) && (value.length() > 0))
            {
                try
                {
                    networkExecutorQueueSize = Integer.parseInt(value);
                } catch (NumberFormatException e)
                {
                }
            }
        } catch (Exception e)
        {
            // ignore
        }
        
        networkExecutor = new ThreadPoolExecutor(CORE_THREADS_CACHED_EXECUTOR, MAX_THREADS_CACHED_EXECUTOR,
        TTL_THREADS_CACHED_EXECUTOR, THREAD_TTL_TIME_UNIT,
        new ArrayBlockingQueue<Runnable>(networkExecutorQueueSize),
        new ThreadFactory()
        {
            public Thread newThread(final Runnable r)
            {
                Thread t = new Thread(r, "Network Queue Processing Thread");
                t.setDaemon(true);
                
                int threadPriority = DEFAULT_NETWORK_THREAD_PRIORITY;
                try
                {
                    String value = Options.value("mdns_network_thread_priority");
                    if ((value == null) || (value.length() == 0))
                    {
                        value = Options.value("mdns_thread_priority");
                    }
                    if ((value != null) && (value.length() == 0))
                    {
                        threadPriority = Integer.parseInt(value);
                    }
                    threadPriority = Integer.parseInt(value);
                } catch (Exception e)
                {
                    // ignore
                }
                t.setPriority(threadPriority);
                t.setContextClassLoader(NetworkProcessor.class.getClassLoader());
                return t;
            }
        });
        networkExecutor.setRejectedExecutionHandler(new RejectedExecutionHandler()
        {
            public void rejectedExecution(final Runnable r, final ThreadPoolExecutor executor)
            {
                Thread t = executor.getThreadFactory().newThread(r);
                t.start();
            }
        });
        networkExecutor.setCorePoolSize(CORE_THREADS_NETWORK_EXECUTOR);
        networkExecutor.setMaximumPoolSize(MAX_THREADS_NETWORK_EXECUTOR);
        networkExecutor.setKeepAliveTime(TTL_THREADS_NETWORK_EXECUTOR, THREAD_TTL_TIME_UNIT);
        networkExecutor.allowCoreThreadTimeOut(true);
    }
    
    
    public static boolean isExecutorOperational()
    {
        return !executor.isShutdown() && !executor.isTerminated() && !executor.isTerminating();
    }
    
    
    public static boolean isNetworkExecutorOperational()
    {
        return !networkExecutor.isShutdown() && !networkExecutor.isTerminated() && !networkExecutor.isTerminating();
    }
    
    
    public static boolean isScheduledExecutorOperational()
    {
        return !scheduledExecutor.isShutdown() && !scheduledExecutor.isTerminated() && !scheduledExecutor.isTerminating();
    }


    public static ScheduledExecutorService getDefaultScheduledExecutor()
    {
        return scheduledExecutor;
    }


    public static void execute(Runnable command)
    {
        executor.execute(command);
    }


    public static ThreadPoolExecutor getDefaultNetworkExecutor()
    {
        return networkExecutor;
    }
}
