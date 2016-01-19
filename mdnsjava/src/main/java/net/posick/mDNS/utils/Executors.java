package net.posick.mDNS.utils;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledFuture;
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
    
    public static final int CORE_THREADS_CACHED_EXECUTOR = 5;
    
    public static final int MAX_THREADS_CACHED_EXECUTOR = Integer.MAX_VALUE;
    
    public static final int TTL_THREADS_CACHED_EXECUTOR = 10000;
    
    public static final int QUEUE_SIZE_CACHED_EXECUTOR = 5;
    
    public static final int DEFAULT_SCHEDULED_THREAD_PRIORITY = Thread.NORM_PRIORITY;
    
    public static final int CORE_THREADS_SCHEDULED_EXECUTOR = 5;
    
    public static final int MAX_THREADS_SCHEDULED_EXECUTOR = Integer.MAX_VALUE;
    
    public static final int TTL_THREADS_SCHEDULED_EXECUTOR = 10000;
    
    public static final TimeUnit THREAD_TTL_TIME_UNIT = TimeUnit.MILLISECONDS;

    private static Executors executors;
    
    private final ScheduledThreadPoolExecutor scheduledExecutor;
    
    private final ThreadPoolExecutor executor;
    
    private final ThreadPoolExecutor networkExecutor;
    
    
    private Executors()
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
        String value = Options.value("mdns_scheduled_core_threads");
        if (value != null && value.length() >= 0)
        {
            try
            {
                scheduledExecutor.setCorePoolSize(Integer.valueOf(value));
            } catch (NumberFormatException e)
            {
                // ignore
            }
        }
        value = Options.value("mdns_scheduled_max_threads");
        if (value != null && value.length() > 0)
        {
            try
            {
                scheduledExecutor.setMaximumPoolSize(Integer.valueOf(value));
            } catch (NumberFormatException e)
            {
                // ignore
            }
        }
        value = Options.value("mdns_scheduled_thread_ttl");
        if (value != null && value.length() > 0)
        {
            try
            {
                scheduledExecutor.setKeepAliveTime(Integer.valueOf(value), THREAD_TTL_TIME_UNIT);
            } catch (NumberFormatException e)
            {
                // ignore
            }
        } else
        {
            scheduledExecutor.setKeepAliveTime(TTL_THREADS_SCHEDULED_EXECUTOR, THREAD_TTL_TIME_UNIT);
        }
        scheduledExecutor.allowCoreThreadTimeOut(true);
        
        int cacheExecutorQueueSize = QUEUE_SIZE_CACHED_EXECUTOR;
        try
        {
            value = Options.value("mdns_cached_thread_queue_size");
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
        value = Options.value("mdns_executor_core_threads");
        if (value != null && value.length() >= 0)
        {
            try
            {
                executor.setCorePoolSize(Integer.valueOf(value));
            } catch (NumberFormatException e)
            {
                // ignore
            }
        }
        value = Options.value("mdns_executor_max_threads");
        if (value != null && value.length() > 0)
        {
            try
            {
                executor.setMaximumPoolSize(Integer.valueOf(value));
            } catch (NumberFormatException e)
            {
                // ignore
            }
        }
        value = Options.value("mdns_executor_thread_ttl");
        if (value != null && value.length() > 0)
        {
            try
            {
                executor.setKeepAliveTime(Integer.valueOf(value), THREAD_TTL_TIME_UNIT);
            } catch (NumberFormatException e)
            {
                // ignore
            }
        } else
        {
            executor.setKeepAliveTime(TTL_THREADS_CACHED_EXECUTOR, THREAD_TTL_TIME_UNIT);
        }
        executor.allowCoreThreadTimeOut(true);
        
        int networkExecutorQueueSize = QUEUE_SIZE_NETWORK_EXECUTOR;
        try
        {
            value = Options.value("mdns_cached_thread_queue_size");
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
        value = Options.value("mdns_network_core_threads");
        if (value != null && value.length() >= 0)
        {
            try
            {
                networkExecutor.setCorePoolSize(Integer.valueOf(value));
            } catch (NumberFormatException e)
            {
                // ignore
            }
        }
        value = Options.value("mdns_network_max_threads");
        if (value != null && value.length() > 0)
        {
            try
            {
                networkExecutor.setMaximumPoolSize(Integer.valueOf(value));
            } catch (NumberFormatException e)
            {
                // ignore
            }
        }
        value = Options.value("mdns_network_thread_ttl");
        if (value != null && value.length() > 0)
        {
            try
            {
                networkExecutor.setKeepAliveTime(Integer.valueOf(value), THREAD_TTL_TIME_UNIT);
            } catch (NumberFormatException e)
            {
                // ignore
            }
        } else
        {
            executor.setKeepAliveTime(TTL_THREADS_NETWORK_EXECUTOR, THREAD_TTL_TIME_UNIT);
        }
        networkExecutor.allowCoreThreadTimeOut(true);
    }
    
    
    public boolean isExecutorOperational()
    {
        return !executor.isShutdown() && !executor.isTerminated() && !executor.isTerminating();
    }
    
    
    public boolean isNetworkExecutorOperational()
    {
        return !networkExecutor.isShutdown() && !networkExecutor.isTerminated() && !networkExecutor.isTerminating();
    }
    
    
    public boolean isScheduledExecutorOperational()
    {
        return !scheduledExecutor.isShutdown() && !scheduledExecutor.isTerminated() && !scheduledExecutor.isTerminating();
    }


    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit)
    {
        return scheduledExecutor.schedule(command, delay, unit);
    }


    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit)
    {
        return scheduledExecutor.scheduleAtFixedRate(command, initialDelay, period, unit);
    }


    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit)
    {
        return scheduledExecutor.scheduleWithFixedDelay(command, initialDelay, delay, unit);
    }


    public void execute(Runnable command)
    {
        executor.execute(command);
    }


    public void executeNetworkTask(Runnable command)
    {
        networkExecutor.execute(command);
    }
    
    
    public static Executors newInstance()
    {
        if (executors == null)
        {
            executors = new Executors();
        }
        
        return executors;
    }
}
