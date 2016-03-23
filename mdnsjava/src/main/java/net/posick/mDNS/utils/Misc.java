package net.posick.mDNS.utils;

import java.io.Closeable;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * This class contains miscellaneous utility methods
 * 
 * @author Steve Posick
 */
public class Misc
{
    public static final void close(Closeable closable)
    {
        if (closable != null)
        {
            try
            {
                closable.close();
            } catch (Exception e)
            {
                // ignore
            }
        }
    }
    
    
    /**
     * Trims trailing whitespace and dots ('.') from the input. 
     * 
     * @param builder The input
     * @return The trimmed input
     */
    public static final StringBuilder trimTrailingDot(StringBuilder builder)
    {
        for (int index = builder.length() - 1; index >= 0; index--)
        {
            char ch = builder.charAt(index);
            if (ch != '.' && !Character.isWhitespace(ch))
            {
                builder.setLength(index);
                break;
            }
        }
        return builder;
    }
    

    /**
     * Trims trailing whitespace and dots ('.') from the input. 
     * 
     * @param builder The input
     * @return The trimmed input
     */
    public static final String trimTrailingDot(String string)
    {
        for (int index = string.length() - 1; index >= 0; index--)
        {
            char ch = string.charAt(index);
            if (ch != '.' && !Character.isWhitespace(ch))
            {
                return string.substring(0, index);
            }
        }
        return string;
    }


    /**
     * Returns the message and stack trace from the provided Throwable  
     * 
     * @param t The Throwable
     * @return The message and stack trace from the provided Throwable
     */
    public static final String throwableToString(Throwable e)
    {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return e.getMessage() + "\nStack Trace:\n" + sw.toString();
    }
    
    
    public static final String unescape(String string)
    {
        StringBuilder output = new StringBuilder();
        char[] chars = string.toCharArray();
        
        boolean escape = false;
        int codePoint = 0;
        int count = 0;
        
        for (char c : chars)
        {
            if (c == '\\')
            {
                escape = true;
                count = 2;
                codePoint = 0; 
                continue;
            } else if (escape == true && count < 0)
            {
                escape = false;
                output.append((char) codePoint);
            }
            
            if (escape)
            {
                if (Character.isDigit(c))
                {
                    codePoint += ((int) c - '0') * Math.pow(10, count);
                }
            } else
            {
                output.append(c);
            }
            count--;
        }
        
        return output.toString();
    }
}
