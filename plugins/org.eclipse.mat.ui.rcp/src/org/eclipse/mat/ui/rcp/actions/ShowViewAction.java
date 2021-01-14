/*******************************************************************************
 * Copyright (c) 2008, 2010 SAP AG.
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
package org.eclipse.mat.ui.rcp.actions;

import org.eclipse.jface.action.Action;
import org.eclipse.mat.ui.util.ErrorHelper;
import org.eclipse.ui.IPluginContribution;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.views.IViewDescriptor;

public class ShowViewAction extends Action implements IPluginContribution
{
    private static int counter = 0;

    private IWorkbenchWindow window;
    private IViewDescriptor desc;

    protected ShowViewAction(IWorkbenchWindow window, IViewDescriptor desc)
    {
        super(""); //$NON-NLS-1$

        setText('&' + desc.getLabel());
        setImageDescriptor(desc.getImageDescriptor());
        setToolTipText('&' + desc.getLabel());
        this.window = window;
        this.desc = desc;
    }

    public void run()
    {
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            try
            {
                if ("org.eclipse.mat.ui.views.InspectorView".equals(desc.getId())) //$NON-NLS-1$
                {
                    page.showView(desc.getId(), secondaryId(desc.getId()), IWorkbenchPage.VIEW_ACTIVATE);
                }
                else
                {
                    page.showView(desc.getId());
                }
            }
            catch (PartInitException e)
            {
                ErrorHelper.logThrowableAndShowMessage(e);
            }
        }
    }

    private static synchronized String secondaryId(String primary)
    {
        return primary + counter++;
    }

    public String getLocalId()
    {
        return desc.getId();
    }

    public String getPluginId()
    {
        return desc instanceof IPluginContribution ? ((IPluginContribution) desc).getPluginId() : null;
    }
}
