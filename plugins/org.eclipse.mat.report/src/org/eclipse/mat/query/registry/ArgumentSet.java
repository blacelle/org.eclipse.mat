/*******************************************************************************
 * Copyright (c) 2008, 2022 SAP AG and IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    SAP AG - initial API and implementation
 *******************************************************************************/
package org.eclipse.mat.query.registry;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.query.IQuery;
import org.eclipse.mat.query.IQueryContext;
import org.eclipse.mat.query.IResult;
import org.eclipse.mat.report.internal.Messages;
import org.eclipse.mat.report.internal.ReportPlugin;
import org.eclipse.mat.util.IProgressListener;
import org.eclipse.mat.util.MessageUtil;

/**
 * Holds a query, all the arguments for a query, and the query context, ready for execution.
 */
public class ArgumentSet
{
    private IQueryContext context;
    private QueryDescriptor query;
    private Map<ArgumentDescriptor, Object> values;

    /* package */ArgumentSet(QueryDescriptor query, IQueryContext context)
    {
        this.query = query;
        this.values = new HashMap<ArgumentDescriptor, Object>();

        for (ArgumentDescriptor argument : query.getArguments())
        {
            if (context.available(argument.getType(), argument.getAdvice()))
                values.put(argument, context.get(argument.getType(), argument.getAdvice()));
        }

        this.context = context;
    }

    /**
     * Generate all the real arguments for an instance of a query,
     * then execute the query.
     * @param listener to show progress
     * @return the result of the query
     * @throws SnapshotException for errors running the query
     */
    public QueryResult execute(IProgressListener listener) throws SnapshotException
    {
        try
        {
            IQuery impl = query.getCommandType().getConstructor().newInstance();

            for (ArgumentDescriptor parameter : query.getArguments())
            {
                Object value = values.get(parameter);

                if (value == null && parameter.isMandatory())
                {
                    value = parameter.getDefaultValue();
                    if (value == null)
                        throw new SnapshotException(MessageUtil.format(
                                        Messages.ArgumentSet_Error_MissingMandatoryArgument, parameter.getName()));
                }

                if (value == null)
                {
                    if (values.containsKey(parameter))
                    {
                        if (ReportPlugin.getDefault().isDebugging())
                        {
                            Logger.getLogger(getClass().getName()).log(Level.INFO,
                                            MessageUtil.format(Messages.ArgumentSet_Msg_NullValue, parameter.getName()));
                        }
                        parameter.getField().set(impl, null);
                    }
                    continue;
                }

                try
                {
                    if (value instanceof ArgumentFactory)
                    {
                        parameter.getField().set(impl, ((ArgumentFactory) value).build(parameter));
                    }
                    else if (parameter.isArray())
                    {
                        List<?> list = (List<?>) value;
                        Object array = Array.newInstance(parameter.getType(), list.size());

                        int ii = 0;
                        for (Object v : list)
                            Array.set(array, ii++, v);

                        parameter.getField().set(impl, array);
                    }
                    else if (parameter.isList())
                    {
                        // handle ArgumentFactory inside list
                        List<?> source = (List<?>) value;
                        List<Object> target = new ArrayList<Object>(source.size());
                        for (int ii = 0; ii < source.size(); ii++)
                        {
                            Object v = source.get(ii);
                            if (v instanceof ArgumentFactory)
                                v = ((ArgumentFactory) v).build(parameter);
                            target.add(v);
                        }

                        parameter.getField().set(impl, target);
                    }
                    else
                    {
                        parameter.getField().set(impl, value);
                    }
                }
                catch (IllegalArgumentException e)
                {
                    throw new SnapshotException(MessageUtil.format(Messages.ArgumentSet_Error_IllegalArgument, value,
                                    value.getClass().getName(), parameter.getName(), parameter.getType().getName()), e);
                }
                catch (IllegalAccessException e)
                {
                    // should not happen as we check accessibility when
                    // registering queries
                    throw new SnapshotException(MessageUtil.format(Messages.ArgumentSet_Error_Inaccessible, parameter
                                    .getName(), parameter.getType().getName()), e);
                }
            }

            IResult result = impl.execute(listener);

            /*
             * Don't close/dispose secondary snapshot as
             * at the moment HTML reports use the snapshot after
             * the query has completed.
             */
            //cleanup(impl);

            return new QueryResult(this.query, this.writeToLine(), result);
        }
        catch (InstantiationException e)
        {
            throw new SnapshotException(MessageUtil.format(Messages.ArgumentSet_Error_Instantiation, query
                            .getCommandType().getName()), e);
        }
        catch (IllegalAccessException e)
        {
            throw new SnapshotException(MessageUtil.format(Messages.ArgumentSet_Error_SetField, query.getCommandType()
                            .getName()), e);
        }
        catch (IProgressListener.OperationCanceledException e)
        {
            throw e; // no nesting!
        }
        catch (SnapshotException e)
        {
            throw e; // no nesting!
        }
        catch (Exception e)
        {
            throw SnapshotException.rethrow(e);
        }
    }

    /**
     * Cleanup argument factories.
     * Some ArgumentFactorys need some cleanup after use.
     * For example {@link SnapshotArgument#build(ArgumentDescriptor)} increments the use count in the factory.
     * @param impl the query
     * @throws IOException
     * @throws IllegalAccessException
     */
    private void cleanup(IQuery impl) throws IOException, IllegalAccessException
    {
        // Tidy up e.g. for SnapshotArgument
        for (ArgumentDescriptor parameter : query.getArguments())
        {
            Object value = values.get(parameter);
            if (value instanceof ArgumentFactory && value instanceof Closeable)
            {
                ((Closeable)value).close();
                // Null out field in query so can't be used
                parameter.getField().set(impl, null);
            }
            else if (parameter.isList() && value instanceof List<?>)
            {
                // handle ArgumentFactory inside list
                List<?> source = (List<?>) value;
                Object t = parameter.getField().get(impl);
                List<Object> target = t instanceof List<?> ? (List<Object>)t : null;
                for (int ii = 0; ii < source.size(); ii++)
                {
                    Object v = source.get(ii);
                    if (v instanceof ArgumentFactory && v instanceof Closeable)
                    {
                        ((Closeable)value).close();
                        if (target != null)
                        {
                            // Null out field in query so can't be used
                            target.set(ii, null);
                        }
                    }
                }
            }
        }
    }

    public String writeToLine()
    {
        StringBuilder answer = new StringBuilder(128);
        answer.append(query.getIdentifier());
        // Argument usage add a space in front
        for (ArgumentDescriptor arg : query.getArguments())
        {
            Object value = values.get(arg);

            if (value == null && !values.containsKey(arg))
                continue;

            if (context.available(arg.getType(), arg.getAdvice()))
                continue;

            if (!context.available(arg.getType(), arg.getAdvice()))
            {
                if (value != null)
                    arg.appendUsage(answer, value);
                else if (arg.getDefaultValue() != null)
                    arg.appendUsage(answer, null);
                else if (arg.isMandatory() && arg.getDefaultValue() != null)
                    arg.appendUsage(answer, arg.getDefaultValue());
            }
        }
        return answer.toString().trim();
    }

    public void setArgumentValue(ArgumentDescriptor arg, Object value)
    {
        values.put(arg, value);
    }

    public void setArgumentValue(String name, Object value)
    {
        ArgumentDescriptor argument = query.getArgumentByName(name);
        if (argument == null)
            throw new RuntimeException(MessageUtil.format(Messages.ArgumentSet_Error_NoSuchArgument, query
                            .getIdentifier(), name));
        setArgumentValue(argument, value);
    }

    public void removeArgumentValue(ArgumentDescriptor arg)
    {
        values.remove(arg);
    }

    public Object getArgumentValue(ArgumentDescriptor desc)
    {
        return values.get(desc);
    }

    // //////////////////////////////////////////////////////////////
    // 
    // //////////////////////////////////////////////////////////////

    public QueryDescriptor getQueryDescriptor()
    {
        return query;
    }

    /* package */IQueryContext getQueryContext()
    {
        return context;
    }

    public boolean isExecutable()
    {
        // all mandatory parameters must be set
        for (ArgumentDescriptor parameter : query.getArguments())
        {
            if (parameter.isMandatory() && values.get(parameter) == null && parameter.getDefaultValue() == null)
                return false;
        }

        return true;
    }

    public List<ArgumentDescriptor> getUnsetArguments()
    {
        List<ArgumentDescriptor> answer = new ArrayList<ArgumentDescriptor>();
        for (ArgumentDescriptor parameter : query.getArguments())
        {
            if (!values.containsKey(parameter))
                answer.add(parameter);
        }
        return answer;
    }

    public String getUnsetUsage()
    {
        StringBuilder answer = new StringBuilder(128);
        for (ArgumentDescriptor parameter : query.getArguments())
        {
            if (context.available(parameter.getType(), parameter.getAdvice()))
                continue;
            if (!values.containsKey(parameter))
                parameter.appendUsage(answer);
        }
        return answer.toString().trim();
    }

    @Override
    public String toString()
    {
        StringBuilder buf = new StringBuilder(256);
        buf.append(query);

        if (!values.isEmpty())
            for (Map.Entry<ArgumentDescriptor, Object> entry : values.entrySet())
                buf.append(" ").append(entry.getKey()).append(" = ").append(entry.getValue()); //$NON-NLS-1$ //$NON-NLS-2$

        return buf.toString();
    }

}
