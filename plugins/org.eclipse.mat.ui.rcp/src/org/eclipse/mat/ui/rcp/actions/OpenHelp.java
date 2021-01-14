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
import org.eclipse.mat.ui.rcp.Messages;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.actions.ActionFactory.IWorkbenchAction;
import org.eclipse.ui.plugin.AbstractUIPlugin;

public class OpenHelp extends Action implements IWorkbenchAction
{
    private static final String HELP = "icons/help.gif"; //$NON-NLS-1$

    public OpenHelp()
    {
        setId(ActionFactory.HELP_CONTENTS.getId());
        setText(Messages.OpenHelp_HelpContents);
        setImageDescriptor(AbstractUIPlugin.imageDescriptorFromPlugin("org.eclipse.mat.ui.rcp", HELP)); //$NON-NLS-1$

    }

    public void run()
    {
        PlatformUI.getWorkbench().getHelpSystem().displayHelpResource("/org.eclipse.mat.ui.help/welcome.html"); //$NON-NLS-1$
    }

    public void dispose()
    {}

}
