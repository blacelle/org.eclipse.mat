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
package org.eclipse.mat.query.refined;

import java.util.List;

import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.query.IResultTree;

/**
 * The result from refining a tree.
 */
public class RefinedTree extends RefinedStructuredResult implements IResultTree
{

    /* package */RefinedTree()
    {}

    // //////////////////////////////////////////////////////////////
    // tree implementation
    // //////////////////////////////////////////////////////////////

    public List<?> getElements()
    {
        try
        {
            return refine(((IResultTree) subject).getElements());
        }
        catch (SnapshotException e)
        {
            throw new RuntimeException(e);
        }
    }

    public boolean hasChildren(Object element)
    {
        return ((IResultTree) subject).hasChildren(element);
    }

    public List<?> getChildren(Object parent)
    {
        try
        {
            return refineUnfiltered(((IResultTree) subject).getChildren(parent));
        }
        catch (SnapshotException e)
        {
            throw new RuntimeException(e);
        }
    }

}
