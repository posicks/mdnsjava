package net.posick.mDNS.utils;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Stack;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The ListenerSupport class implements a performant, thread safe, listener subsystem
 * that does not create temporary objects during the event dispatch process. The order in which
 * listeners are registered determines the order by which the listeners are called during event
 * dispatch. A listener may halt the delivery of events to subsequent listeners by throwing a
 * StopDispatchException.
 * 
 * @author Steve Posick
 */
@SuppressWarnings("unchecked")
public class ListenerProcessor<T> implements Closeable
{
    private static final Logger logger = Logger.getLogger(ListenerProcessor.class.getName());
    
    public static class StopDispatchException extends Exception
    {
        private static final long serialVersionUID = 201401211841L;
        
        
        public StopDispatchException()
        {
            super();
        }
    }
    
    
    protected static class Dispatcher implements InvocationHandler
    {
        ListenerProcessor<?> processor;
        
        
        protected Dispatcher(final ListenerProcessor<?> processor)
        {
            this.processor = processor;
        }
        
        
        public Object invoke(final Object proxy, final Method method, final Object[] args)
        throws Throwable
        {
            Object[] tempListeners = processor.listeners;
            for (Object listener : tempListeners)
            {
                try
                {
                    method.invoke(listener, args);
                } catch (IllegalArgumentException e)
                {
                    logger.log(Level.WARNING, e.getMessage(), e);
                    throw e;
                } catch (IllegalAccessException e)
                {
                    logger.log(Level.WARNING, e.getMessage(), e);
                    throw e;
                } catch (InvocationTargetException e)
                {
                    if (e.getTargetException() instanceof StopDispatchException)
                    {
                        break;
                    } else
                    {
                        logger.log(Level.WARNING, e.getTargetException().getMessage(), e.getTargetException());
                        throw e.getTargetException();
                    }
                } catch (Exception e)
                {
                    logger.log(Level.WARNING, e.getMessage(), e);
                    throw e;
                }
            }
            
            return null;
        }
    }
    
    private final Class<T> iface;
    
    private Object[] listeners = new Object[0];
    
    private T dispatcher;
    
    
    public ListenerProcessor(final Class<T> iface)
    {
        this.iface = iface;
        if (!iface.isInterface())
        {
            throw new IllegalArgumentException("\"" + iface.getName() + "\" is not an interface.");
        }
    }
    
    
    public void close()
    throws IOException
    {
        for (int i = 0; i < this.listeners.length; i++ )
        {
            this.listeners[i] = null;
        }
        this.listeners = new Object[0];
    }
    
    
    public T getDispatcher()
    {
        if (dispatcher == null)
        {
            dispatcher = (T) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[] {iface}, new Dispatcher(this));
        }
        return dispatcher;
    }
    
    
    public synchronized T registerListener(final T listener)
    {
        // Make sure the listener is not null and that it implements the Interface
        if ((listener != null) && iface.isAssignableFrom(listener.getClass()))
        {
            // Check to ensure the listener does not exist
            for (int index = 0; index < listeners.length; index++ )
            {
                if ((listeners[index] == listener) || listeners[index].equals(listener))
                {
                    // already registered
                    return (T) listeners[index];
                }
            }
            
            T[] temp = (T[]) Arrays.copyOf(listeners, listeners.length + 1);
            temp[temp.length - 1] = listener;
            this.listeners = temp;
            
            return listener;
        } else
        {
            return null;
        }
    }
    
    
    public synchronized T unregisterListener(final T listener)
    {
        if (listener != null)
        {
            T[] temp = (T[]) Arrays.copyOf(listeners, listeners.length);
            
            // Find listener
            for (int index = 0; index < temp.length; index++ )
            {
                if ((temp[index] == listener) || temp[index].equals(listener))
                {
                    Object foundListener = temp[index];
                    
                    // Found listener, delete it and shift remaining listeners back one position.
                    System.arraycopy(temp, index + 1, temp, index, temp.length - index - 1);
                    this.listeners = Arrays.copyOf(temp, temp.length - 1);
                    
                    return (T) foundListener;
                }
            }
        }
        
        return null;
    }
    
    
    protected static final Class<?>[] getAllInterfaces(final Class<?> clazz)
    {
        LinkedHashSet<Class<?>> set = new LinkedHashSet<Class<?>>();
        Stack<Class<?>> stack = new Stack<Class<?>>();
        
        stack.push(clazz);
        
        if (clazz.isInterface())
        {
            set.add(clazz);
        }
        
        while (!stack.isEmpty())
        {
            Class<?> cls = stack.pop();
            Class<?>[] interfaces = cls.getInterfaces();
            if ((interfaces != null) && (interfaces.length > 0))
            {
                for (Class<?> iface : interfaces)
                {
                    if (set.add(iface))
                    {
                        stack.push(iface);
                    }
                }
            }
            
            Class<?> superClass = cls.getSuperclass();
            if (superClass != null)
            {
                if (superClass.isInterface())
                {
                    set.add(superClass);
                }
                stack.push(superClass);
            }
        }
        
        return set.toArray(new Class[set.size()]);
    }
}
